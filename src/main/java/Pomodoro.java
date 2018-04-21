import java.io.IOException;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import pomodoro.Timer;
import pomodoro.Timer.Status;
import pomodoro.TimerManager;
import pomodoro.TimerManagerFactory;

public final class Pomodoro {

    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String COMMAND_PROMPT = "pomodoro> ";

    public static void main(String[] args) {
        final boolean ansi;
        try {
            ansi = isAnsiRequested(args);
        } catch (final IllegalArgumentException e) {
            System.err.println("Illegal argument. Try: java Main [-ansi]");
            return;
        }

        final boolean isConsole = (System.console() != null);
        final Consumer<String> promptPrinter = (prompt) -> {
            if (!isConsole) {
                return;
            }
            System.out.print(prompt);
            System.out.flush();
        };

        final Consumer<String> notificationPrinter = msg -> {
            System.out.println("\n" + (ansi ? ANSI_GREEN : "") + msg + (ansi ? ANSI_RESET : ""));
            promptPrinter.accept(COMMAND_PROMPT);
            System.out.flush();
        };

        final Consumer<String> errorPrinter = msg -> {
            System.out.println((ansi ? ANSI_RED : "") + msg + (ansi ? ANSI_RESET : ""));
            System.out.flush();
        };

        TimerManagerFactory.Builder builder = TimerManagerFactory.builder();

        builder.add(new TimerManager.Listener() {
            @Override
            public void onTimerRemoved(final Timer timer) {
                notificationPrinter.accept(String.format("%s: removed", timer.getName()));
            }
            @Override
            public void onTimerSuspended(final Timer timer) {
                notificationPrinter.accept(String.format("%s: suspended", timer.getName()));
            }
            @Override
            public void onTimerResumed(final Timer timer) {
                notificationPrinter.accept(String.format("%s: resumed", timer.getName()));
            }
            @Override
            public void onActivityStarted(
                    final Timer timer, final int timeToGo, final TimeUnit timeUnit) {
                notificationPrinter.accept(String.format("%s: activity started (%s %s)",
                        timer.getName(), timeToGo, timeUnit));
            }
            @Override
            public void onActivityEnded(final Timer timer, final int pomodoroCount) {
                notificationPrinter.accept(String.format("%s: activity ended (%s pomodoros)",
                        timer.getName(), pomodoroCount));
            }
            @Override
            public void onBreakStarted(
                    final Timer timer, final int timeToGo, final TimeUnit timeUnit) {
                notificationPrinter.accept(String.format("%s: break started (%s %s)",
                        timer.getName(), timeToGo, timeUnit));
            }
            @Override
            public void onTimerStopped(final Timer timer, final int pomodoroCount) {
                notificationPrinter.accept(String.format("%s: timer stopped (%s pomodoros)",
                        timer.getName(), pomodoroCount));
            }
            @Override
            public void onTimerEnded(final Timer timer, final int pomodoroCount) {
                notificationPrinter.accept(String.format("%s: timer ended (%s pomodoros)",
                        timer.getName(), pomodoroCount));
            }
        });

        final Clip clip = getPingSoundClip();
        if (isConsole && clip != null) {
            builder.add(new TimerManager.Listener() {
                @Override
                public void onActivityEnded(final Timer timer, final int pomodoroCount) {
                    clip.loop(1);
                }
            });
        }

        // One may want to skip this line (noisy listener)
        builder.add(new TimerManager.Listener() {
            @Override
            public void onTick(
                    final Timer timer,
                    final Status status,
                    final int secondsElapsed,
                    final int timeToGo,
                    final TimeUnit timeUnit) {
                notificationPrinter.accept(
                        String.format(
                                "%s: %s %s of %s (%s %s)",
                                timer.getName(),
                                status,
                                secondsElapsed,
                                timeUnit.toSeconds(timeToGo),
                                timeToGo,
                                timeUnit));
            }
        });

        final TimerManager manager = builder.timeUnit(TimeUnit.SECONDS).build();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (clip != null) clip.close();
                manager.shutdown();
            }
        });

        promptPrinter.accept("Welcome to Pomodoro. Type 'help' for help.\n");
        promptPrinter.accept(COMMAND_PROMPT);

        final Scanner scanner = new Scanner(System.in);
        try {
            while (scanner.hasNextLine()) {
                try {
                    if (!parseAndExecute(scanner.nextLine(), manager)) {
                        break;
                    }
                } catch (final TimerManager.Error | RuntimeException e) {
                    errorPrinter.accept(e.getMessage());
                }
                promptPrinter.accept(COMMAND_PROMPT);
            }
        } finally {
            scanner.close();
        }
    }

    private static final String INVALID_SYNTAX_MESSAGE = "Invalid syntax.";
    private static boolean parseAndExecute(
            final String command,
            final TimerManager manager)
            throws TimerManager.Error {
        final String[] split = command.split("\\s+");
        switch (split[0]) {
            case "":
                return true;
            case "help":
                System.out.println(" 1. a [silent] <name> [<secs1> <secs2>...] ... add timer");
                System.out.println(" 2. ls ....................................... list timers");
                System.out.println(" 3. mv <name> <new_name> ..................... rename timer");
                System.out.println(" 4. su <name> ................................ suspend timer");
                System.out.println(" 5. re <name> ................................ resume timer");
                System.out.println(" 6. rm <name> ................................ remove timer");
                System.out.println(" 7. li [<name>]............................... start listening to timer(s)");
                System.out.println(" 8. stfu [<name>] ............................ stop listening to timer(s)");
                System.out.println(" 9. cl ....................................... clear (remove all timers)");
                System.out.println("10. sl <secs> ................................ don't use this (tests only)");
                System.out.println("11. bye ...................................... exit Pomodoro");
                return true;
            case "a":
                if (split.length < 2) {
                    throw new RuntimeException(INVALID_SYNTAX_MESSAGE);
                }
                boolean silent = false;
                int nameIdx = 1;
                if ("silent".equals(split[1])) {
                    silent = true;
                    nameIdx = 2;
                }
                final int[] executionSchema = new int[split.length - (nameIdx + 1)];
                for (int i = nameIdx + 1; i < split.length; i++) {
                    executionSchema[i - ((nameIdx + 1))] = Integer.valueOf(split[i]);
                }
                manager.add(split[nameIdx], !silent, executionSchema);
                return true;
            case "ls":
                System.out.println(
                        manager
                            .list()
                            .stream()
                            .map(t -> String.format("%-20s %-10s %d",
                                    t.getName(), t.getStatus(), t.getPomodoroCount()))
                            .collect(Collectors.joining("\n")));
                return true;
            case "mv":
                if (split.length != 3) {
                    throw new RuntimeException(INVALID_SYNTAX_MESSAGE);
                }
                manager.rename(split[1], split[2]);
                return true;
            case "su":
                if (split.length != 2) {
                    throw new RuntimeException(INVALID_SYNTAX_MESSAGE);
                }
                manager.suspend(split[1]);
                return true;
            case "re":
                if (split.length != 2) {
                    throw new RuntimeException(INVALID_SYNTAX_MESSAGE);
                }
                manager.resume(split[1]);
                return true;
            case "rm":
                if (split.length != 2) {
                    throw new RuntimeException(INVALID_SYNTAX_MESSAGE);
                }
                manager.remove(split[1]);
                return true;
            case "li":
                if (split.length == 1) {
                    manager.talk();
                    return true;
                }
                manager.talk(split[1]);
                return true;
            case "stfu":
                if (split.length == 1) {
                    manager.stfu();
                    return true;
                }
                manager.stfu(split[1]);
                return true;
            case "sl":
                if (split.length != 2) {
                    throw new RuntimeException(INVALID_SYNTAX_MESSAGE);
                }
                try {
                    Thread.sleep(1000 * Integer.valueOf(split[1]));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return true;
            case "cl":
                manager.clear();
                return true;
            case "bye":
                manager.shutdown();
                return false;
            default:
                throw new RuntimeException("Invalid command.");
        }
    }

    private static Clip getPingSoundClip() {
        AudioInputStream ais = null;
        try {
            ais = AudioSystem.getAudioInputStream(
                    Pomodoro.class.getResourceAsStream("ping.wav"));
            final Clip clip = AudioSystem.getClip();
            clip.open(ais);
            return clip;
        } catch (UnsupportedAudioFileException | IOException | LineUnavailableException e) {
            return null;
        } finally {
            if (ais != null) {
                try {
                    ais.close();
                } catch (IOException e) {
                    System.err.println("WARNING: failed to close AudioInputStream.");
                }
            }
        }
    }

    private static boolean isAnsiRequested(final String[] args) throws IllegalArgumentException {
        if (args.length == 0) {
            return false;
        }
        if ("-ansi".equals(args[0])) {
            return true;
        }
        throw new IllegalArgumentException();
    }
}
