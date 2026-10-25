package oss.relay.eventtiming.systemtest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

/** Retained build evidence from one separate-process black-box verification. */
final class BlackBoxEvidence {
    private static final Pattern TIMESTAMPED_LOG =
            Pattern.compile("\\d{8}-\\d{6}(?:-\\d{2,})?\\.txt");
    private static final Pattern COMPACT_LOG_LINE = Pattern.compile(
            "(?m)^\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - "
                    + "\\[(?:TRACE|DEBUG|INFO|WARN|ERROR)\\] - "
                    + ".+ - \\[[^\\]]+\\]\\r?$");

    private final File directory;

    private BlackBoxEvidence(File directory) {
        this.directory = directory;
    }

    static BlackBoxEvidence create(String rootPath, String verificationId)
            throws IOException {
        File directory = new File(rootPath, verificationId).getAbsoluteFile();
        deleteRecursively(directory);
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create evidence directory: " + directory);
        }
        return new BlackBoxEvidence(directory);
    }

    File directory() {
        return directory;
    }

    File file(String name) {
        return new File(directory, name);
    }

    /**
     * Verifies both observable console logging and the application's retained
     * timestamped runtime log. The original log files remain in the evidence tree.
     */
    void verifyRuntimeLogging(String processOutput) throws IOException {
        if (processOutput == null || processOutput.trim().isEmpty()) {
            throw new AssertionError("Application process output is empty");
        }
        assertCompactLogText(processOutput, "application process output");
        if (processOutput.contains("INFO: HTTP IF-03 listening")
                || processOutput.contains("INFO: WebSocket IF-03 listening")) {
            throw new AssertionError(
                    "Application process output still contains default JUL formatting");
        }
        if (!processOutput.contains(" - [INFO] - HTTP IF-03 listening on 127.0.0.1:")
                || !processOutput.contains(
                        " - [INFO] - WebSocket IF-03 listening on 127.0.0.1:")) {
            throw new AssertionError(
                    "Application process output does not contain compact IF-03 startup records");
        }

        File logDirectory = file("logs");
        File[] logs = logDirectory.listFiles(
                (dir, name) -> TIMESTAMPED_LOG.matcher(name).matches());
        if (logs == null || logs.length == 0) {
            throw new AssertionError(
                    "No timestamped application runtime log found in " + logDirectory);
        }
        Arrays.sort(logs, Comparator.comparing(File::getName));

        StringBuilder retained = new StringBuilder();
        for (File log : logs) {
            if (!log.isFile() || log.length() == 0L) {
                throw new AssertionError("Application runtime log is empty: " + log);
            }
            retained.append(new String(
                    Files.readAllBytes(log.toPath()),
                    StandardCharsets.UTF_8));
        }

        String retainedText = retained.toString();
        assertCompactLogText(retainedText, "retained application runtime log");
        if (!retainedText.contains(" - [INFO] - HTTP IF-03 listening on 127.0.0.1:")
                || !retainedText.contains(
                        " - [INFO] - WebSocket IF-03 listening on 127.0.0.1:")) {
            throw new AssertionError(
                    "Retained application runtime log does not contain IF-03 startup records");
        }
    }

    void writeProcessOutput(String output) {
        writeQuietly("application-output.txt", output);
    }

    void writeResult(boolean passed, Throwable failure) {
        StringBuilder result = new StringBuilder();
        result.append(passed ? "PASS" : "FAIL").append(System.lineSeparator());
        if (failure != null) {
            result.append(failure.getClass().getName());
            if (failure.getMessage() != null) {
                result.append(": ").append(failure.getMessage());
            }
            result.append(System.lineSeparator());
        }
        writeQuietly("result.txt", result.toString());
    }

    private static void assertCompactLogText(String text, String description) {
        if (!COMPACT_LOG_LINE.matcher(text).find()) {
            throw new AssertionError(
                    description
                            + " does not contain an HH:mm:ss.SSS - [LEVEL] - message - [source] line");
        }
    }

    private void writeQuietly(String name, String text) {
        try {
            Files.write(
                    file(name).toPath(),
                    text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            System.err.println(
                    "Unable to write black-box evidence "
                            + file(name)
                            + ": "
                            + ex.getMessage());
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) {
                throw new IOException("Unable to list evidence directory: " + file);
            }
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        if (!file.delete()) {
            throw new IOException("Unable to delete previous evidence: " + file);
        }
    }
}
