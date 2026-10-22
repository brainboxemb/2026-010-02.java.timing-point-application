package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ExecutionMetrics;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Bounded FIFO queue whose items are processed serially by one fixed worker.
 *
 * <p>The queue stores caller-owned values of type {@code T}. The backing
 * executor never sees those values; it only schedules {@link #queueWorker}.
 * While that worker is scheduled or running, adding more items only extends the
 * queue. A second worker for the same lane is never scheduled.</p>
 *
 * <p>One worker run processes at most {@code maxItemsPerRun} items. If items
 * remain afterwards, the same worker is scheduled once more. This gives other
 * lanes sharing the same executor a chance to run between batches.</p>
 *
 * @param <T> queued item type
 */
public final class SerialLane<T> implements AutoCloseable {

    /** Queue entry with only the timestamp needed for queue metrics. */
    private static final class QueuedItem<T> {
        private final T item;
        private final long queuedAtNanos;

        private QueuedItem(T item) {
            this.item = item;
            queuedAtNanos = System.nanoTime();
        }
    }

    private final String laneName;
    private final Executor executor;
    private final ArrayBlockingQueue<QueuedItem<T>> queue;
    private final int maxItemsPerRun;
    private final Consumer<T> itemProcessor;
    private final Runnable queueWorker = this::runQueueWorker;
    private final ExecutionMetrics metrics;

    /** Java thread currently running this lane's worker, or null while idle. */
    private volatile Thread executingThread;

    private boolean accepting;
    private boolean workerScheduled;

    /**
     * Creates one typed serial queue on an externally owned executor.
     *
     * @param capacity maximum number of waiting items
     * @param maxItemsPerRun maximum number of items processed by one worker run
     * @param laneName diagnostic lane name
     * @param executor physical execution dependency
     * @param itemProcessor caller-owned processing for one item
     */
    public SerialLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            Executor executor,
            Consumer<T> itemProcessor) {

        failIfInvalidArgument(capacity >= 1, "capacity must be positive");
        failIfInvalidArgument(maxItemsPerRun >= 1, "maxItemsPerRun must be positive");
        failIfInvalidArgument(
                laneName != null && !laneName.trim().isEmpty(),
                "laneName must not be blank");
        failIfInvalidArgument(executor != null, "executor must not be null");
        failIfInvalidArgument(itemProcessor != null, "itemProcessor must not be null");

        this.laneName = laneName.trim();
        this.executor = executor;
        queue = new ArrayBlockingQueue<QueuedItem<T>>(capacity);
        this.maxItemsPerRun = maxItemsPerRun;
        this.itemProcessor = itemProcessor;
        metrics = new ExecutionMetrics(queue::size);
    }

    /** Starts accepting items. A drained lane may be started again. */
    public synchronized void start() {
        failIfIllegalState(!accepting, "SerialLane %s is already running", laneName);
        failIfIllegalState(
                !workerScheduled,
                "SerialLane %s is still draining",
                laneName);
        accepting = true;
    }

    /** Immediate result of attempting to queue one item. */
    public enum OfferResult {
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    /**
     * Attempts to add one item without waiting for queue capacity.
     *
     * <p>The first item added while idle schedules the fixed worker. Further
     * items do not schedule another worker.</p>
     */
    public OfferResult offer(T item) {
        failIfInvalidArgument(item != null, "item must not be null");

        boolean scheduleWorker = false;
        synchronized (this) {
            if (!accepting) {
                metrics.recordNotRunning();
                return OfferResult.NOT_RUNNING;
            }

            if (!queue.offer(new QueuedItem<T>(item))) {
                metrics.recordFull();
                return OfferResult.FULL;
            }

            metrics.recordQueued(queue.size());
            if (!workerScheduled) {
                workerScheduled = true;
                scheduleWorker = true;
            }
        }

        if (scheduleWorker) {
            scheduleWorker();
        }
        return OfferResult.ACCEPTED;
    }

    /** Runs one bounded batch on the backing executor. */
    private void runQueueWorker() {
        int itemCount;
        synchronized (this) {
            itemCount = Math.min(queue.size(), maxItemsPerRun);
        }

        executingThread = Thread.currentThread();
        try {
            for (int index = 0; index < itemCount; index++) {
                QueuedItem<T> item = queue.poll();
                if (item == null) {
                    break;
                }
                process(item);
            }
        } finally {
            executingThread = null;
            finishWorkerRun();
        }
    }

    /** Processes one item and records its queue/execution timing. */
    private void process(QueuedItem<T> queuedItem) {
        long startedAtNanos = System.nanoTime();
        try {
            itemProcessor.accept(queuedItem.item);
        } finally {
            long finishedAtNanos = System.nanoTime();
            metrics.recordCompleted(
                    elapsedNanos(queuedItem.queuedAtNanos, startedAtNanos),
                    elapsedNanos(startedAtNanos, finishedAtNanos));
        }
    }

    /**
     * Marks this worker idle when the queue is empty, otherwise schedules one
     * later worker run for the remaining items.
     */
    private void finishWorkerRun() {
        synchronized (this) {
            if (queue.isEmpty()) {
                workerScheduled = false;
                notifyAll();
                return;
            }
        }

        scheduleWorker();
    }

    /** Schedules the fixed worker; executor rejection stops further admission. */
    private void scheduleWorker() {
        try {
            executor.execute(queueWorker);
        } catch (RejectedExecutionException ex) {
            synchronized (this) {
                accepting = false;
                workerScheduled = false;
                queue.clear();
                notifyAll();
            }
            throw ex;
        }
    }

    /** Returns whether this lane currently accepts new items. */
    public synchronized boolean isRunning() {
        return accepting;
    }

    /** True only while this Java thread is processing an item for this lane. */
    public boolean isExecutingOnCurrentThread() {
        return executingThread == Thread.currentThread();
    }

    /** Returns item-queue metrics for this lane. */
    public ExecutionMetrics metrics() {
        return metrics;
    }

    /**
     * Stops admission immediately and discards queued items without waiting for
     * the currently executing item.
     *
     * <p>This is intended for cancellation-oriented users such as cooperative
     * task runners. Data lanes should normally use {@link #close()} so already
     * accepted data is drained.</p>
     */
    public synchronized void stop() {
        accepting = false;
        queue.clear();
    }
    /**
     * Stops admission and waits until all already accepted items are processed.
     */
    @Override
    public synchronized void close() {
        accepting = false;

        boolean interrupted = false;
        while (workerScheduled) {
            try {
                wait();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long elapsedNanos(long startedNanos, long finishedNanos) {
        long elapsed = finishedNanos - startedNanos;
        return elapsed < 0L ? 0L : elapsed;
    }
}
