package oss.relay.timing.timingpoint.infra.logging;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Bounded non-blocking JUL handoff to one logging writer thread.
 *
 * <p>Publisher threads only snapshot the LogRecord and attempt bounded queue
 * admission. Formatting and delegate I/O happen on {@code tp-inf-logging}.
 * Queue overload never blocks application/timing lanes. When a WARN/ERROR
 * arrives to a full queue, one queued lower-severity record is preferentially
 * removed so the operationally more important record can be retained.</p>
 *
 * <p>This handler owns the writer thread but not delegate lifecycle. Closing it
 * drains accepted records and flushes delegates; the composition owner closes
 * owned sinks such as the retained file handler afterwards.</p>
 */
final class AsyncLogHandler extends Handler {
    private static final String THREAD_NAME = "tp-inf-logging";

    private final ArrayBlockingQueue<LogRecord> queue;
    private final List<Handler> delegates;
    private final long flushIntervalMillis;
    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicLong unreportedDroppedRecords = new AtomicLong();
    private final Thread writerThread;
    private final ConsolePromptCoordinator promptCoordinator;

    private volatile boolean accepting = true;

    AsyncLogHandler(
            List<Handler> delegates,
            int queueCapacity,
            long flushIntervalMillis) {
        this(
                delegates,
                queueCapacity,
                flushIntervalMillis,
                null);
    }

    AsyncLogHandler(
            List<Handler> delegates,
            int queueCapacity,
            long flushIntervalMillis,
            ConsolePromptCoordinator promptCoordinator) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("delegates must not be empty");
        }
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException("queueCapacity must be greater than zero");
        }
        if (flushIntervalMillis <= 0L) {
            throw new IllegalArgumentException(
                    "flushIntervalMillis must be greater than zero");
        }

        this.delegates = new ArrayList<Handler>(delegates.size());
        for (Handler delegate : delegates) {
            if (delegate == null) {
                throw new IllegalArgumentException(
                        "delegates must not contain null");
            }
            this.delegates.add(delegate);
        }
        this.queue = new ArrayBlockingQueue<LogRecord>(queueCapacity);
        this.flushIntervalMillis = flushIntervalMillis;
        this.promptCoordinator = promptCoordinator;
        setLevel(Level.ALL);

        writerThread = new Thread(this::writeLoop, THREAD_NAME);
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || !accepting || !isLoggable(record)) {
            return;
        }

        LogRecord snapshot = snapshot(record);
        boolean queued = queue.offer(snapshot);

        if (!queued
                && snapshot.getLevel().intValue() >= Level.WARNING.intValue()
                && removeOneLowerSeverityRecord()) {
            queued = queue.offer(snapshot);
        }

        if (!queued) {
            recordDrop();
        }
    }

    private boolean removeOneLowerSeverityRecord() {
        Iterator<LogRecord> iterator = queue.iterator();
        while (iterator.hasNext()) {
            LogRecord queued = iterator.next();
            if (queued.getLevel().intValue() < Level.WARNING.intValue()
                    && queue.remove(queued)) {
                recordDrop();
                return true;
            }
        }
        return false;
    }

    private void recordDrop() {
        droppedRecords.incrementAndGet();
        unreportedDroppedRecords.incrementAndGet();
    }

    long droppedRecordCount() {
        return droppedRecords.get();
    }

    int queuedRecordCount() {
        return queue.size();
    }

    @Override
    public void flush() {
        flushDelegates();
    }

    @Override
    public void close() {
        if (!accepting) {
            return;
        }

        /*
         * Do not interrupt the writer: an interrupt while a Java NIO file write
         * or flush is in progress can close the underlying channel. The bounded
         * poll timeout wakes an idle writer within flushIntervalMillis; active
         * sink I/O is allowed to finish normally before the accepted queue is
         * drained.
         */
        accepting = false;
        if (Thread.currentThread() != writerThread) {
            boolean interrupted = false;
            while (writerThread.isAlive()) {
                try {
                    writerThread.join();
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writeLoop() {
        long lastFlushNanos = System.nanoTime();
        boolean dirty = false;
        try {
            while (accepting || !queue.isEmpty()) {
                redrawPromptIfDue();

                long waitMillis = flushIntervalMillis;
                if (promptCoordinator != null) {
                    waitMillis =
                            promptCoordinator.nextWakeDelayMillis(
                                    waitMillis);
                }

                LogRecord record = null;
                try {
                    record = queue.poll(
                            waitMillis,
                            TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    if (accepting) {
                        continue;
                    }
                }

                reportDroppedRecords();
                if (record != null) {
                    publishToDelegates(record);
                    dirty = true;
                    if (record.getLevel().intValue()
                            >= Level.WARNING.intValue()) {
                        flushDelegates();
                        dirty = false;
                        lastFlushNanos = System.nanoTime();
                    }
                }

                redrawPromptIfDue();

                long now = System.nanoTime();
                if (dirty
                        && now - lastFlushNanos
                        >= TimeUnit.MILLISECONDS.toNanos(flushIntervalMillis)) {
                    flushDelegates();
                    dirty = false;
                    lastFlushNanos = now;
                }
            }
        } finally {
            redrawPromptIfDue();
            reportDroppedRecords();
            flushDelegates();
        }
    }

    private void redrawPromptIfDue() {
        if (promptCoordinator != null) {
            promptCoordinator.redrawPromptIfDue();
        }
    }

    private void reportDroppedRecords() {
        long dropped = unreportedDroppedRecords.getAndSet(0L);
        if (dropped == 0L) {
            return;
        }

        LogRecord warning = new LogRecord(
                Level.WARNING,
                "Async logging dropped " + dropped
                        + " record(s) because the bounded queue was full");
        warning.setLoggerName(AsyncLogHandler.class.getName());
        warning.setSourceClassName(AsyncLogHandler.class.getName());
        warning.setSourceMethodName("publish");
        publishToDelegates(warning);
        flushDelegates();
    }

    private void publishToDelegates(LogRecord record) {
        for (Handler delegate : delegates) {
            try {
                if (delegate.isLoggable(record)) {
                    delegate.publish(record);
                }
            } catch (RuntimeException ex) {
                reportError(
                        "Unable to publish asynchronous log record",
                        ex,
                        ErrorManager.WRITE_FAILURE);
            }
        }
    }

    private void flushDelegates() {
        for (Handler delegate : delegates) {
            try {
                delegate.flush();
            } catch (RuntimeException ex) {
                reportError(
                        "Unable to flush asynchronous log delegate",
                        ex,
                        ErrorManager.FLUSH_FAILURE);
            }
        }
    }

    private static LogRecord snapshot(LogRecord source) {
        LogRecord copy = new LogRecord(
                source.getLevel(),
                source.getMessage());
        copy.setLoggerName(source.getLoggerName());
        copy.setMillis(source.getMillis());
        copy.setSequenceNumber(source.getSequenceNumber());
        copy.setSourceClassName(source.getSourceClassName());
        copy.setSourceMethodName(source.getSourceMethodName());
        copy.setThreadID(source.getThreadID());
        copy.setThrown(source.getThrown());
        copy.setResourceBundle(source.getResourceBundle());
        copy.setResourceBundleName(source.getResourceBundleName());

        Object[] parameters = source.getParameters();
        copy.setParameters(
                parameters == null
                        ? null
                        : parameters.clone());
        return copy;
    }
}
