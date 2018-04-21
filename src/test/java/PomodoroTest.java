import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import pomodoro.Timer;
import pomodoro.Timer.Status;
import pomodoro.TimerManager;
import pomodoro.TimerManager.Error;
import pomodoro.TimerManagerFactory;

public final class PomodoroTest {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface Test {}

    private static final boolean VERBOSE =
            "true".equals(System.getProperties().getProperty("verbose", "false"));
    private static final int MAX_ACTIVE_TIMERS = 3;

    public static void main(String[] args) throws InterruptedException, Error {

        final Method[] methods = PomodoroTest.class.getDeclaredMethods();
        for (Method method : methods) {
            if (method.isAnnotationPresent(Test.class)) {
                System.out.println("Testing " + method.getName() + "...");
                try {
                    method.invoke(null);
                } catch (final IllegalAccessException |
                        IllegalArgumentException |
                        InvocationTargetException e) {
                    throw new RuntimeException(e.getCause());
                }
                System.out.println();
            }
        }
        System.out.println("All tests passed.");
    }

    @Test
    public static void duplicateTimerAdditionCauseError() throws Error {
        final TimerManager manager = getManager(getEmptyNet());
        try {
            manager.add("sam");
            manager.add("per");
            manager.add("sam");
        } catch(final Error e) {
            return;
        } finally {
            manager.shutdown();
        }
        azzert(false, "Duplicate timers should have cause Error, but they didn't.");
    }

    @Test
    public static void timerManagerReturnsImmutableList() throws Error {
        final TimerManager manager = getManager(getEmptyNet());
        try {
            manager.add("sam");
            manager.add("per");
            manager.add("lin");
            final List<Timer> timers = manager.list();
            final Timer timer = timers.get(0);
            manager.remove(timer.getName());
            timers.add(timer);
        } catch(final UnsupportedOperationException e) {
            return;
        } finally {
            manager.shutdown();
        }
        azzert(false, "TimerManager.list() returned mutable list.");
    }

    @Test
    public static void emptyExecutionSchemeNotifiesTimerEndImmediately() throws Error {
        final Set<String> net = getEmptyNet();
        final TimerManager manager = getManager(net);
        try {
            manager.add("sam");
            azzert(
                    waitForCatch(net, 500, fingerprint("sam", "onTimerEnded", Status.ENDED, 0)),
                    "Failed to receive onTimerEnded");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public static void executionSchemeWithZeroLengthsEndsImmediatelyWithNoTicks() throws Error {
        final Set<String> net = getEmptyNet();
        final TimerManager manager = getManager(net, 0, 0, 0, 0);
        try {
            manager.add("sam");
            azzert(
                    waitForCatch(net, 1000, fingerprint("sam", "onTimerEnded", Status.ENDED, 2)),
                    "Failed to receive onTimerEnded.");
            azzert(
                    !waitForCatch(net, 100, fingerprint("sam", "onTick", Status.ACTIVE, -1)),
                    "Not expecting ticks, but still got one.");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public static void normalEecutionSchemeProducesTicksAndReturnsCorrectPomodoroCount() throws Error {
        final Set<String> net = getEmptyNet();
        final TimerManager manager = getManager(net, 2, 1);
        try {
            manager.add("sam");
            azzert(
                    waitForCatch(net, 4000,
                            fingerprint("sam", "onTimerEnded", Status.ENDED, 1),
                            fingerprint("sam", "onTick", Status.ACTIVE, -1)),
                    "Failed to execute execution scheme correctly.");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public static void lengthyTimerStoppedAfterReceivingFirstTickYieldsNoPomodoros() throws Error {
        final Set<String> net = getEmptyNet();
        final TimerManager manager = getManager(net, 25, 5, 25, 15);
        try {
            manager.add("sam");
            azzert(
                    waitForCatch(net, 4000,
                            fingerprint("sam", "onTick", Status.ACTIVE, -1)),
                    "Failed to receive ticks.");
            final Timer timer = manager.list().get(0);
            manager.remove("sam");
            azzert(timer.getPomodoroCount() == 0, "No pomodoros expected, still got some.");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    public static void excesiveTimerWaitsForItsTurnAndExecutesNormally() throws Error {
        final Set<String> net = getEmptyNet();
        final TimerManager manager = getManager(net, 2, 1, 2, 1, 2);
        try {
            for (int i = 0; i < MAX_ACTIVE_TIMERS; i++) {
                manager.add("timer" + i);
            }
            manager.add("sam");
            azzert(
                    waitForCatch(net, 2000,
                            fingerprint("timer0", "onTick", Status.ACTIVE, -1)),
                    "Failed to receive tick from a timer.");
            Timer timer = manager.list().stream().filter(t -> t.getName().equals("sam")).findFirst().get();
            azzert(Status.INACTIVE.equals(timer.getStatus()), "Timer not in expected state.");
            azzert(
                    waitForCatch(net, 10*1000,
                            fingerprint("timer0", "onTimerEnded", Status.ENDED, 3)),
                    "Failed to receive onTimerEnded OR the correct pomodoro count.");
            azzert(
                    waitForCatch(net, 2000,
                            fingerprint("sam", "onTick", Status.ACTIVE, -1)),
                    "Failed to receive tick from a timer that should be active by now.");
            azzert(
                    waitForCatch(net, 10*1000,
                            fingerprint("sam", "onTimerEnded", Status.ENDED, 3)),
                    "Failed to receive onTimerEnded OR the correct pomodoro count.");
        } finally {
            manager.shutdown();
        }
    }

    private static void azzert(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static boolean waitForCatch(
            final Set<String> net,
            final long waitingTimeInMillis,
            final String... expectedCach) {
        final Set<String> expectedSet = new HashSet<>();
        Collections.addAll(expectedSet, expectedCach);

        long startTime = System.currentTimeMillis();
        while (true) {
            if (net.containsAll(expectedSet)) {
                return true;
            }
            if (System.currentTimeMillis() - startTime > waitingTimeInMillis) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static TimerManager getManager(
            final Set<String> net,
            final int... executionScheme) {
        return TimerManagerFactory
                .builder()
                .maxActiveTimers(MAX_ACTIVE_TIMERS)
                .timeUnit(TimeUnit.SECONDS)
                .executionScheme(executionScheme)
                .talk()
                .add(getCatchingListener(net))
                .build();
    }

    private static Set<String> getEmptyNet() {
        return new CopyOnWriteArraySet<>();
    }

    private static TimerManager.Listener getCatchingListener(
            final Set<String> net) {
        return new TimerManager.Listener() {
            @Override
            public void onTick(Timer timer, Status status, int secondsElapsed, int timeToGo, TimeUnit timeUnit) {
                add(net, fingerprint(timer, "onTick", status, -1));
            }
            @Override
            public void onTimerRemoved(Timer timer) {
                add(net, fingerprint(timer, "onTimerRemoved", null, -1));
            }
            @Override
            public void onTimerSuspended(Timer timer) {
                add(net, fingerprint(timer, "onTimerSuspended", null, -1));
            }
            @Override
            public void onTimerResumed(Timer timer) {
                add(net, fingerprint(timer, "onTimerResumed", null, -1));
            }
            @Override
            public void onActivityStarted(Timer timer, int timeToGo, TimeUnit timeUnit) {
                add(net, fingerprint(timer, "onActivityStarted", null, -1));
            }
            @Override
            public void onActivityEnded(Timer timer, int pomodoroCount) {
                add(net, fingerprint(timer, "onActivityEnded", null, pomodoroCount));
            }
            @Override
            public void onBreakStarted(Timer timer, int timeToGo, TimeUnit timeUnit) {
                add(net, fingerprint(timer, "onBreakStarted", null, -1));
            }
            @Override
            public void onTimerStopped(Timer timer, int pomodoroCount) {
                add(net, fingerprint(timer, "onTimerStopped", timer.getStatus(), pomodoroCount));
            }
            @Override
            public void onTimerEnded(Timer timer, int pomodoroCount) {
                add(net, fingerprint(timer, "onTimerEnded", timer.getStatus(), pomodoroCount));
            }
        };
    }

    private static void add(final Set<String> net, final String fingerprint) {
        net.add(fingerprint);
        if (VERBOSE) System.out.println(fingerprint);
    }

    private static String fingerprint(
            final Timer t, final String methodName, final Status status, final int pomodoroCount) {
        return fingerprint(t.getName(), methodName, status, pomodoroCount);
    }

    private static String fingerprint(
            final String timerName, final String methodName, final Status status, final int pomodoroCount) {
        return String.format("%s:%s:%s:%s", timerName, methodName, status, pomodoroCount);
    }
}
