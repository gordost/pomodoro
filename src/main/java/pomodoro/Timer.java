package pomodoro;

public interface Timer {

    public enum Status {
        INACTIVE,
        ACTIVE,
        AT_BREAK,
        SUSPENDED,
        STOPPED,
        ENDED
    }

    String getName();
    int getPomodoroCount();
    Status getStatus();
}
