package pomodoro;
import java.util.List;
import java.util.concurrent.TimeUnit;

public interface TimerManager {

    Timer add(final String timerName, final int... executionSchema) throws Error;
    Timer add(final String timerName, final boolean talk, final int... executionSchema) throws Error;
    void suspend(final String timerName) throws Error;
    void resume(final String timerName) throws Error;
    void remove(final String timerName) throws Error;
    void rename(final String timerName, final String newName) throws Error;
    List<Timer> list();
    void talk();
    void talk(final String timerName) throws Error;
    void stfu();
    void stfu(final String timerName) throws Error;
    void clear();
    void shutdown();

    public interface Builder {
        Builder add(final Listener listener);
        Builder maxActiveTimers(final int maxActiveTimers);
        Builder timeUnit(final TimeUnit timeUnit);
        Builder talk();
        Builder executionScheme(final int... executionScheme);
        TimerManager build();
    }

    public interface Listener {
        default void onTick(
                final Timer timer,
                final Timer.Status status,
                final int secondsElapsed,
                final int timeToGo,
                final TimeUnit timeUnit) {}

        default void onTimerRemoved(final Timer timer) {}

        default void onTimerSuspended(final Timer timer) {}
        default void onTimerResumed(final Timer timer) {}
        default void onActivityStarted(
                final Timer timer, final int timeToGo, final TimeUnit timeUnit) {}
        default void onActivityEnded(final Timer timer, final int pomodoroCount) {}
        default void onBreakStarted(
                final Timer timer, final int timeToGo, final TimeUnit timeUnit) {}
        default void onTimerStopped(final Timer timer, final int pomodoroCount) {}
        default void onTimerEnded(final Timer timer, final int pomodoroCount) {}
    }

    @SuppressWarnings("serial")
    public class Error extends java.lang.Exception{
        public Error(final String message) {
            super(message);
        }
    }
}
