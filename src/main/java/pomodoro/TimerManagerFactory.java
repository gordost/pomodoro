package pomodoro;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import pomodoro.TimerManager.Listener;

public final class TimerManagerFactory {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder implements TimerManager.Builder {
        private static final int MAX_THREADS = 5;

        private final List<Listener> listeners = new ArrayList<>();
        private int threadCount = MAX_THREADS;
        private boolean talk = false;
        private TimeUnit timeUnit = TimeUnit.MINUTES;
        private int[] executionScheme = new int[] {25, 5, 25, 5, 25, 5, 25, 5, 15};

        @Override
        public Builder add(final Listener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("Listeners cannot be null.");
            }
            listeners.add(listener);
            return this;
        }

        @Override
        public Builder maxActiveTimers(final int maxActiveTimers) {
            if (maxActiveTimers <= 0) {
                throw new IllegalArgumentException("maxActiveTimers: illegal value.");
            }
            this.threadCount = maxActiveTimers;
            return this;
        }

        @Override
        public Builder timeUnit(final TimeUnit timeUnit) {
            if (timeUnit.toSeconds(1) < 1) {
                throw new IllegalArgumentException("Time units shorter than a second are not allowed.");
            }
            this.timeUnit = timeUnit;
            return this;
        }

        @Override
        public Builder talk() {
            this.talk = true;
            return this;
        }

        @Override
        public Builder executionScheme(final int... executionScheme) {
            for (int i = 0; i < executionScheme.length; i++) {
                if (executionScheme[i] < 0) {
                    throw new IllegalArgumentException("No negative values allowed.");
                }
            }
            this.executionScheme = Arrays.copyOf(executionScheme, executionScheme.length);
            return this;
        }

        @Override
        public TimerManager build() {
            return new TimerManagerImpl(
                    listeners,
                    threadCount,
                    talk,
                    executionScheme,
                    timeUnit);
        }
    }

    private final static class TimerManagerImpl implements TimerManager {

        private final List<TimerImpl> timers = new CopyOnWriteArrayList<>();
        private final ExecutorService notifierExecutor = Executors.newSingleThreadExecutor();

        private final ExecutorService timerExecutor;

        private volatile boolean talk;
        private final List<Listener> listeners;
        private final BlockingQueue<Runnable> notifQueue;

        private final int[] defaultExecutionScheme;
        private final TimeUnit timeUnit;

        private final Object lock = new Object();

        private final Listener proxyListener = new Listener() {
            @Override
            public void onTick(
                    final Timer timer,
                    final Timer.Status status,
                    final int secondsElapsed,
                    final int timeToGo,
                    final TimeUnit timeUnit) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(
                                l -> l.onTick(timer, status, secondsElapsed, timeToGo, timeUnit));});
            }
            @Override
            public void onTimerRemoved(final Timer timer) {
                notifQueue.offer(() -> {listeners.stream().forEach(l -> l.onTimerRemoved(timer));});
            }
            @Override
            public void onTimerSuspended(final Timer timer) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(l -> l.onTimerSuspended(timer));});
            }
            @Override
            public void onTimerResumed(final Timer timer) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(l -> l.onTimerResumed(timer));});
            }
            @Override
            public void onActivityStarted(final Timer timer, final int timeToGo, final TimeUnit timeUnit) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(
                                l -> l.onActivityStarted(timer, timeToGo, timeUnit));});
            }
            @Override
            public void onActivityEnded(final Timer timer, final int pomodoroCount) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(l -> l.onActivityEnded(timer, pomodoroCount));});
            }
            @Override
            public void onBreakStarted(final Timer timer, final int timeToGo, final TimeUnit timeUnit) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(
                                l -> l.onBreakStarted(timer, timeToGo, timeUnit));});
            }
            @Override
            public void onTimerStopped(final Timer timer, final int pomodoroCount) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(l -> l.onTimerStopped(timer, pomodoroCount));});
            }
            @Override
            public void onTimerEnded(final Timer timer, final int pomodoroCount) {
                notifQueue.offer(
                        () -> {listeners.stream().forEach(l -> l.onTimerEnded(timer, pomodoroCount));});
            }
        };
        private TimerManagerImpl(
                final List<Listener> listeners,
                final int threadCount,
                final boolean talk,
                final int[] defaultExecutionScheme,
                final TimeUnit timeUnit) {
            this.defaultExecutionScheme = defaultExecutionScheme;
            this.timeUnit = timeUnit;
            this.listeners = new CopyOnWriteArrayList<>(listeners);
            this.talk = talk;
            this.timerExecutor = Executors.newFixedThreadPool(threadCount);
            this.notifQueue = new ArrayBlockingQueue<>(100 * threadCount);
            this.notifierExecutor.execute(() -> {
                while (true) {
                    try {
                        notifQueue.take().run();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }

        @Override
        public Timer add(
                final String timerName,
                final boolean talk,
                final int... executionScheme) throws Error {
            if (timerName == null || timerName.trim().isEmpty()) {
                throw new IllegalArgumentException("Illegal timer name.");
            }
            synchronized(lock) {
                if (timers.stream().anyMatch(t -> t.getName().equals(timerName))) {
                    throw new Error("Timer already exists.");
                }
                final TimerImpl timer = new TimerImpl(
                        timerName,
                        talk,
                        timeUnit,
                        executionScheme.length > 0 ? executionScheme : defaultExecutionScheme);
                timers.add(timer);
                timerExecutor.execute(timer);
                return timer;
            }
        }

        @Override
        public Timer add(final String timerName, final int... executionScheme) throws Error {
            return add(timerName, true, executionScheme);
        }

        @Override
        public void suspend(final String timerName) throws Error {
            findTimer(timerName).suspend();
        }

        @Override
        public void resume(final String timerName) throws Error {
            findTimer(timerName).resume();
        }

        @Override
        public void remove(final String timerName) throws Error {
            synchronized (lock) {
                TimerImpl timer = findTimer(timerName);
                timer.stop();
                timers.remove(timer);
                proxyListener.onTimerRemoved(timer);
            }
        }

        @Override
        public void rename(final String timerName, final String newName) throws Error {
            findTimer(timerName).setName(newName);
        }

        @Override
        public List<Timer> list() {
            return Collections.unmodifiableList(timers);
        }

        @Override
        public void talk() {
            talk = true;
        }

        @Override
        public void talk(String timerName) throws Error {
            findTimer(timerName).talk();
        }

        @Override
        public void stfu() {
            talk = false;
        }

        @Override
        public void stfu(String timerName) throws Error {
            findTimer(timerName).stfu();
        }

        @Override
        public void clear() {
            timers.stream().forEach(t -> t.stop());
            timers.clear();
        }

        @Override
        public void shutdown() {
            timerExecutor.shutdown();
            clear();
            if (!awaitFullTermination(timerExecutor)) {
                notifierExecutor.shutdownNow();
                Thread.currentThread().interrupt();
                return;
            }
            notifierExecutor.shutdownNow();
            if (!awaitFullTermination(notifierExecutor)) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        private boolean awaitFullTermination(final ExecutorService executor) {
            executor.shutdown();
            try {
                awaitTermination(executor);
            } catch (InterruptedException e) {
                executor.shutdownNow();
                return false;
            }
            return true;
        }

        private void awaitTermination(final ExecutorService executor) throws InterruptedException {
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    executor.awaitTermination(60, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                throw e;
            }
        }

        private TimerImpl findTimer(final String timerName) throws Error {
            Optional<TimerImpl> timerOpt =
                    timers.stream().filter(t -> timerName.equals(t.getName())).findFirst();
            if (!timerOpt.isPresent()) {
                throw new Error("Timer not found.");
            }
            return timerOpt.get();
        }

        private class TimerImpl implements Timer, Runnable {
            private volatile String name;
            private volatile boolean talk;
            private final TimeUnit timeUnit;
            private final int[] executionScheme;

            private volatile Status status = Status.INACTIVE;
            private volatile int pomodoroCount = 0;
            private volatile boolean suspended = false;
            private volatile boolean stopped = false;


            private final Object lock = new Object();

            private TimerImpl(
                    final String name,
                    final boolean talk,
                    final TimeUnit timeUnit,
                    final int... executionScheme) {
                this.name = name;
                this.talk = talk;
                this.executionScheme = executionScheme;
                this.timeUnit = timeUnit;
            }

            @Override
            public String getName() {
                return name;
            }

            private void setName(final String name) {
                this.name = name;
            }

            @Override
            public int getPomodoroCount() {
                return pomodoroCount;
            }

            @Override
            public Status getStatus() {
                if (stopped) {
                    return Status.STOPPED;
                }
                if (suspended) {
                    return Status.SUSPENDED;
                }
                return status;
            }

            private boolean isTalking() {
                return TimerManagerImpl.this.talk && talk;
            }

            private void suspend() throws Error {
                if (suspended) {
                    throw new Error("Failed to suspend timer: already suspended.");
                }
                if (stopped) {
                    throw new Error("Failed to suspend timer: timer stopped.");
                }
                suspended = true;
                proxyListener.onTimerSuspended(this);
            }

            private void resume() throws Error {
                if (!suspended) {
                    throw new Error("Failed to resume timer: not suspended.");
                }
                if (stopped) {
                    throw new Error("Failed to resume timer: timer stopped.");
                }
                suspended = false;
                proxyListener.onTimerResumed(this);
            }

            private void stop() {
                stopped = true;
            }

            private void talk() {
                talk = true;
            }

            private void stfu() {
                talk = false;
            }

            private boolean checkStoppedStateAndNotifyListener() {
                if (stopped) {
                    if (isTalking()) {
                        proxyListener.onTimerStopped(TimerImpl.this, getPomodoroCount());
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void run() {
                final long startpoint = System.currentTimeMillis();
                long checkpoint = startpoint;
                long accumulated = 0;
                int lastTickSent = 0;
                for (int i = 0; i < executionScheme.length; i++) {
                    if (checkStoppedStateAndNotifyListener()) return;
                    status = (i % 2 == 0) ? Status.ACTIVE : Status.AT_BREAK;
                    if (isTalking() && Status.ACTIVE.equals(status)) {
                        proxyListener.onActivityStarted(this, executionScheme[i], timeUnit);
                    }
                    if (isTalking() && Status.AT_BREAK.equals(status)) {
                        proxyListener.onActivityEnded(this, pomodoroCount);
                        proxyListener.onBreakStarted(this, executionScheme[i], timeUnit);
                    }
                    while (true) {
                        if (checkStoppedStateAndNotifyListener()) return;
                        try {
                            Thread.sleep(100);
                        } catch (final InterruptedException e) {
                            stop();
                            Thread.currentThread().interrupt();
                            return;
                        }
                        long currentTime = System.currentTimeMillis();
                        long diff = currentTime - checkpoint;
                        checkpoint += diff;
                        if (suspended) {
                            continue;
                        }
                        accumulated += diff;
                        if (accumulated - lastTickSent * 1000 > 1000) {
                            lastTickSent = (int) (accumulated / 1000);
                            if (isTalking()) {
                                proxyListener.onTick(
                                        this, status, lastTickSent, executionScheme[i], timeUnit);
                            }
                        }
                        if (accumulated >= timeUnit.toMillis(executionScheme[i])) {
                            pomodoroCount = i/2 + 1;
                            accumulated = 0;
                            lastTickSent = 0;
                            break;
                        }
                    }
                }
                this.status = Status.ENDED;
                if (isTalking()) {
                    proxyListener.onTimerEnded(this, pomodoroCount);
                }
            }
        }
    }
}
