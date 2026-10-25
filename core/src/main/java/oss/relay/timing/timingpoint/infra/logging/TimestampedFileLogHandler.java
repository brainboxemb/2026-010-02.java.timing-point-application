package oss.relay.timing.timingpoint.infra.logging;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Retained JUL file sink using one timestamped text file per session/rotation.
 *
 * <p>Primary filenames are yyyyMMdd-HHmmss.txt. A numeric suffix is used only when another
 * file must be opened within the same second.</p>
 */
final class TimestampedFileLogHandler extends Handler {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final int rotateBytes;
    private final int retainedFiles;
    private final Clock clock;
    private final CompactLogFormatter formatter;

    private OutputStream output;
    private long bytesWritten;
    private Path currentFile;

    TimestampedFileLogHandler(Path directory, int rotateBytes, int retainedFiles)
            throws IOException {
        this(directory, rotateBytes, retainedFiles, Clock.systemDefaultZone());
    }

    /**
     * Package-private deterministic-clock seam for filename/retention tests.
     *
     * <p>Runtime logging uses the constructor above with the system clock.</p>
     */
    TimestampedFileLogHandler(
            Path directory,
            int rotateBytes,
            int retainedFiles,
            Clock clock) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("log directory must not be null");
        }
        if (rotateBytes <= 0) {
            throw new IllegalArgumentException("rotateBytes must be greater than zero");
        }
        if (retainedFiles <= 0) {
            throw new IllegalArgumentException("retainedFiles must be greater than zero");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }

        this.directory = directory;
        this.rotateBytes = rotateBytes;
        this.retainedFiles = retainedFiles;
        this.clock = clock;
        this.formatter = new CompactLogFormatter(clock.getZone());
        setFormatter(formatter);
        setLevel(java.util.logging.Level.ALL);

        Files.createDirectories(directory);
        openNextFile();
    }

    Path currentFile() {
        return currentFile;
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (record == null || !isLoggable(record)) {
            return;
        }

        byte[] bytes = formatter.format(record).getBytes(StandardCharsets.UTF_8);
        try {
            if (bytesWritten > 0 && bytesWritten + bytes.length > rotateBytes) {
                rotate();
            }
            // The async logging writer owns flush cadence. Keeping this sink
            // buffered prevents one filesystem flush per application log record.
            output.write(bytes);
            bytesWritten += bytes.length;
        } catch (IOException ex) {
            reportError("Unable to write retained runtime log", ex, ErrorManager.WRITE_FAILURE);
        }
    }

    @Override
    public synchronized void flush() {
        if (output == null) {
            return;
        }
        try {
            output.flush();
        } catch (IOException ex) {
            reportError("Unable to flush retained runtime log", ex, ErrorManager.FLUSH_FAILURE);
        }
    }

    @Override
    public synchronized void close() {
        if (output == null) {
            return;
        }
        try {
            output.flush();
            output.close();
        } catch (IOException ex) {
            reportError("Unable to close retained runtime log", ex, ErrorManager.CLOSE_FAILURE);
        } finally {
            output = null;
        }
    }

    private void rotate() throws IOException {
        if (output != null) {
            output.flush();
            output.close();
        }
        output = null;
        openNextFile();
    }

    private void openNextFile() throws IOException {
        String base = FILE_TIME.format(LocalDateTime.now(clock));
        Path candidate = directory.resolve(base + ".txt");
        int collision = 1;
        while (Files.exists(candidate)) {
            candidate = directory.resolve(
                    base + "-" + String.format("%02d", collision++) + ".txt");
        }

        output = new BufferedOutputStream(Files.newOutputStream(
                candidate,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE));
        currentFile = candidate;
        bytesWritten = 0L;
        enforceRetention();
    }

    private void enforceRetention() throws IOException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.txt")) {
            for (Path file : stream) {
                if (isTimestampedLog(file.getFileName().toString())) {
                    files.add(file);
                }
            }
        }

        Collections.sort(files, Comparator.comparing(path -> path.getFileName().toString()));
        int remove = files.size() - retainedFiles;
        for (Path file : files) {
            if (remove <= 0) {
                break;
            }
            if (file.equals(currentFile)) {
                continue;
            }
            if (Files.deleteIfExists(file)) {
                remove--;
            }
        }
    }

    private static boolean isTimestampedLog(String name) {
        return name.matches("\\d{8}-\\d{6}(?:-\\d{2,})?\\.txt");
    }
}
