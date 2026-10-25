package oss.relay.timing.timingpoint.infra.logging;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LoggingTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appliesCompactFormatterToConsoleAndRestoresPreviousFormatter() throws Exception {
        Logger root = Logger.getLogger("");
        ConsoleHandler console = new ConsoleHandler();
        Formatter previousFormatter = console.getFormatter();
        root.addHandler(console);
        try {
            File logDirectory = temporaryFolder.newFolder("console");
            LoggingConfig config = new LoggingConfig(
                    LoggingLevel.INFO,
                    new LoggingFileConfig(logDirectory.getAbsolutePath(), 4096, 1));

            try (Logging ignored = Logging.start(config)) {
                assertTrue(console.getFormatter() instanceof CompactLogFormatter);

                LogRecord record = new LogRecord(Level.INFO, "Application lifecycle state=RUNNING");
                record.setSourceClassName(
                        "oss.relay.timing.timingpoint.runtime.TimingApplicationLifecycle");
                record.setSourceMethodName("start");
                String line = console.getFormatter().format(record);
                assertTrue(line.matches(
                        "\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - \\[INFO\\] - "
                                + "Application lifecycle state=RUNNING - "
                                + "\\[runtime\\.TimingApplicationLifecycle\\.start\\]\\R"));
            }

            assertSame(previousFormatter, console.getFormatter());
        } finally {
            root.removeHandler(console);
            console.close();
        }
    }

    @Test
    public void keepsLastProjectPackageSegmentForSourceContext() {
        LogRecord record = new LogRecord(Level.INFO, "Remote terminal listening");
        record.setSourceClassName(
                "oss.relay.timing.timingpoint.presentation.interfaces.shell.RemoteShellServer");
        record.setSourceMethodName("start");

        assertEquals(
                "shell.RemoteShellServer.start",
                CompactLogFormatter.source(record));
    }

    @Test
    public void keepsNonProjectSourceQualified() {
        LogRecord record = new LogRecord(Level.INFO, "External component");
        record.setSourceClassName("com.example.transport.ExternalAdapter");
        record.setSourceMethodName("start");

        assertEquals(
                "com.example.transport.ExternalAdapter.start",
                CompactLogFormatter.source(record));
    }

    @Test
    public void rotatesWithinConfiguredRetention() throws Exception {
        File logDirectory = temporaryFolder.newFolder("rotating");
        LoggingConfig config = new LoggingConfig(
                LoggingLevel.INFO,
                new LoggingFileConfig(logDirectory.getAbsolutePath(), 512, 2));

        try (Logging ignored = Logging.start(config)) {
            Logger logger = Logger.getLogger("test.a08.rotation");
            for (int i = 0; i < 100; i++) {
                logger.info("rotation-" + i + "-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
            }
        }

        File[] generations = timestampedLogs(logDirectory);
        assertTrue(generations.length >= 1);
        assertTrue("retention exceeded: " + generations.length, generations.length <= 2);
        for (File generation : generations) {
            assertTrue(generation.getName().matches(
                    "\\d{8}-\\d{6}(?:-\\d{2,})?\\.txt"));
        }
    }

    @Test
    public void repeatedWallClockTimestampNeverOverwritesExistingLog() throws Exception {
        File logDirectory = temporaryFolder.newFolder("repeated-clock");
        Path existing = logDirectory.toPath().resolve("20250514-101657.txt");
        Files.write(existing, "previous-session".getBytes(StandardCharsets.UTF_8));

        Clock repeatedClock = Clock.fixed(
                Instant.parse("2025-05-14T08:16:57Z"),
                ZoneId.of("Europe/Amsterdam"));

        Path current;
        TimestampedFileLogHandler handler =
                new TimestampedFileLogHandler(logDirectory.toPath(), 4096, 10, repeatedClock);
        try {
            current = handler.currentFile();
            assertEquals("20250514-101657-01.txt", current.getFileName().toString());
            assertTrue(Files.exists(current));
        } finally {
            handler.close();
        }

        assertEquals(
                "previous-session",
                new String(Files.readAllBytes(existing), StandardCharsets.UTF_8));
        assertTrue(Files.exists(current));
    }

    @Test
    public void staleWallClockCannotPruneActiveLog() throws Exception {
        File logDirectory = temporaryFolder.newFolder("stale-clock-retention");
        Files.write(
                logDirectory.toPath().resolve("20300101-000000.txt"),
                "future-a".getBytes(StandardCharsets.UTF_8));
        Files.write(
                logDirectory.toPath().resolve("20400101-000000.txt"),
                "future-b".getBytes(StandardCharsets.UTF_8));

        Clock staleClock = Clock.fixed(
                Instant.parse("2025-05-14T08:16:57Z"),
                ZoneId.of("Europe/Amsterdam"));

        Path current;
        TimestampedFileLogHandler handler =
                new TimestampedFileLogHandler(logDirectory.toPath(), 4096, 2, staleClock);
        try {
            current = handler.currentFile();
            assertEquals("20250514-101657.txt", current.getFileName().toString());
            assertTrue(Files.exists(current));
        } finally {
            handler.close();
        }

        assertTrue(Files.exists(current));
        assertEquals(2, timestampedLogs(logDirectory).length);
    }

    @Test
    public void formatsRetainedRecordLikeExistingOperatorLogs() {
        LogRecord record = new LogRecord(Level.INFO, "WebServer started");
        record.setMillis(1747210618109L);
        record.setSourceClassName("com.bata.ebart.next.web.WebServer");
        record.setSourceMethodName("<init>");

        String line = new CompactLogFormatter(java.time.ZoneId.of("Europe/Amsterdam"))
                .format(record);

        assertTrue(line.matches(
                "\\d{2}:\\d{2}:\\d{2}\\.\\d{3} - \\[INFO\\] - WebServer started - "
                        + "\\[com\\.bata\\.ebart\\.next\\.web\\.WebServer\\.<init>\\]"
                        + "\\R"));
    }

    @Test
    public void asynchronousHandlerNeverWaitsForSlowDelegateIo() throws Exception {
        BlockingHandler slow = new BlockingHandler();
        AsyncLogHandler async = new AsyncLogHandler(
                Arrays.<Handler>asList(slow),
                2,
                50L);
        try {
            async.publish(new LogRecord(Level.INFO, "first"));
            assertTrue(slow.started.await(1L, TimeUnit.SECONDS));

            CountDownLatch returned = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                async.publish(new LogRecord(Level.INFO, "second"));
                async.publish(new LogRecord(Level.INFO, "third"));
                async.publish(new LogRecord(Level.INFO, "fourth"));
                returned.countDown();
            });

            producer.start();
            assertTrue(
                    "publisher waited for slow logging delegate",
                    returned.await(250L, TimeUnit.MILLISECONDS));
            producer.join(1000L);
            assertTrue(async.droppedRecordCount() >= 1L);
        } finally {
            slow.release.countDown();
            async.close();
        }
    }

    @Test
    public void asynchronousHandlerDrainsAcceptedRecordsBeforeClose() {
        RecordingHandler sink = new RecordingHandler();
        AsyncLogHandler async = new AsyncLogHandler(
                Arrays.<Handler>asList(sink),
                16,
                50L);

        for (int i = 0; i < 10; i++) {
            async.publish(
                    new LogRecord(
                            Level.INFO,
                            "record-" + i));
        }

        async.close();

        assertEquals(10, sink.messages.size());
        for (int i = 0; i < 10; i++) {
            assertEquals(
                    "record-" + i,
                    sink.messages.get(i));
        }
    }

    @Test
    public void warnRecordReplacesQueuedLowerSeverityOnOverflow()
            throws Exception {
        BlockingHandler slow = new BlockingHandler();
        AsyncLogHandler async = new AsyncLogHandler(
                Arrays.<Handler>asList(slow),
                1,
                50L);
        try {
            async.publish(new LogRecord(Level.INFO, "running"));
            assertTrue(slow.started.await(1L, TimeUnit.SECONDS));

            async.publish(new LogRecord(Level.INFO, "low-priority"));
            async.publish(new LogRecord(Level.WARNING, "important"));

            slow.release.countDown();
            async.close();

            assertTrue(slow.messages.contains("important"));
            assertTrue(
                    "expected lower-severity overflow to be counted",
                    async.droppedRecordCount() >= 1L);
        } finally {
            slow.release.countDown();
            async.close();
        }
    }

    private static final class RecordingHandler extends Handler {
        private final List<String> messages =
                new ArrayList<String>();

        @Override
        public synchronized void publish(LogRecord record) {
            messages.add(record.getMessage());
        }

        @Override
        public void flush() {
            // No external sink.
        }

        @Override
        public void close() {
            // Test owns lifecycle.
        }
    }

    private static final class BlockingHandler extends Handler {
        private final CountDownLatch started =
                new CountDownLatch(1);
        private final CountDownLatch release =
                new CountDownLatch(1);
        private final List<String> messages =
                new ArrayList<String>();
        private final AtomicInteger flushes =
                new AtomicInteger();

        @Override
        public void publish(LogRecord record) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            synchronized (this) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
            flushes.incrementAndGet();
        }

        @Override
        public void close() {
            release.countDown();
        }
    }

    private static File[] timestampedLogs(File directory) {
        File[] files = directory.listFiles(
                (dir, name) -> name.matches(
                        "\\d{8}-\\d{6}(?:-\\d{2,})?\\.txt"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

}
