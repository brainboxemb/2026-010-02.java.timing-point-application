package oss.relay.eventtiming.testclient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Small local Development Client log, independent from SI-01 LoggingServer. */
final class ClientLog implements AutoCloseable {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private final BufferedWriter writer;
    private volatile int threshold;
    private volatile String level;
    private final List<String> history = new ArrayList<>();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private ClientLog(BufferedWriter writer, String level) {
        this.writer = writer;
        setLevel(level);
    }

    static ClientLog open(Path directory, String level) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        Files.createDirectories(directory);
        String stem = FILE_TIME.format(LocalDateTime.now());
        Path file = directory.resolve(stem + ".txt");
        int suffix = 1;
        while (Files.exists(file)) {
            file = directory.resolve(stem + "-" + suffix + ".txt");
            suffix++;
        }
        BufferedWriter writer = Files.newBufferedWriter(
                file,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        return new ClientLog(writer, level);
    }

    void trace(String message) {
        log("TRACE", message);
    }

    void debug(String message) {
        log("DEBUG", message);
    }

    void info(String message) {
        log("INFO", message);
    }

    void warn(String message) {
        log("WARN", message);
    }

    void error(String message) {
        log("ERROR", message);
    }

    String level() {
        return level;
    }

    void setLevel(String value) {
        String normalized = normalizeLevel(value);
        threshold = severity(normalized);
        level = normalized;
    }

    synchronized String snapshot() {
        return String.join("", history);
    }

    void subscribe(Consumer<String> listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
    }

    private synchronized void log(String level, String message) {
        if (severity(level) < threshold) {
            return;
        }
        String line = LINE_TIME.format(LocalTime.now())
                + " - [" + level + "] - "
                + (message == null ? "" : message)
                + " - [" + callerSource() + "]"
                + System.lineSeparator();
        history.add(line);
        try {
            writer.write(line);
            writer.flush();
        } catch (IOException ex) {
            System.err.println("Development Client log write failed: " + ex.getMessage());
        }
        for (Consumer<String> listener : listeners) {
            listener.accept(line);
        }
    }

    private static String callerSource() {
        return STACK_WALKER.walk(frames -> frames
                .filter(frame -> frame.getDeclaringClass() != ClientLog.class)
                .findFirst()
                .map(frame -> compactSource(
                        frame.getClassName(),
                        frame.getMethodName()))
                .orElse("testclient.ClientLog.log"));
    }

    private static String compactSource(String className, String methodName) {
        int classSeparator = className.lastIndexOf('.');
        if (classSeparator < 0) {
            return className + "." + methodName;
        }
        int packageSeparator = className.lastIndexOf('.', classSeparator - 1);
        String displayClass = packageSeparator < 0
                ? className
                : className.substring(packageSeparator + 1);
        return displayClass + "." + methodName;
    }

    private static String normalizeLevel(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        severity(normalized);
        return normalized;
    }

    private static int severity(String value) {
        String level = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (level) {
            case "TRACE" -> 0;
            case "DEBUG" -> 1;
            case "INFO" -> 2;
            case "WARN" -> 3;
            case "ERROR" -> 4;
            default -> throw new IllegalArgumentException("Unsupported client log level: " + value);
        };
    }

    @Override
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // Best-effort desktop-client shutdown.
        }
    }
}
