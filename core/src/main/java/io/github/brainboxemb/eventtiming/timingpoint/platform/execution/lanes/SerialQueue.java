package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Bounded FIFO item queue serviced by one fixed worker on a {@link SerialLane}.
 *
 * <p>The queue contains caller-owned items, not {@link Runnable} work. Queueing
 * an item only makes the fixed worker eligible to run. This class owns the
 * scheduling guard, so callers never need their own "worker scheduled" flag and
 * cannot accidentally schedule two queue workers for the same item queue.</p>
 *
 * @param <T> queued item type
 */
public final class SerialQueue<T> implements AutoCloseable {

    /** Immediate result of attempting to queue one item. */
    public enum OfferResult {
        ACCEPTED,
        FULL,
        NOT_RUNNING
    }

    private final ArrayBlockingQueue<T> queue;
    private final int maxItemsPerRun;
    private final SerialLane lane;
    private final Consumer<T> itemProcessor;
    private final Runnable worker = this::runWorker;

    private boolean accepting;
    private boolean workerScheduled;

    /** Creates an item queue using the supplied lane's configured capacity and batch size. */
    public SerialQueue(
            SerialLane lane,
            Consumer<T> itemProcessor) {

        this(
                capacity(lane),
                maxItemsPerRun(lane),
                lane,
                itemProcessor);
    }

    /**
     * Creates one bounded item queue serviced by the supplied serial lane.
     *
     * @param capacity maximum number of queued items
     * @param maxItemsPerRun maximum number of items handled by one worker run
     * @param lane serial scheduling boundary used by the fixed worker
     * @param itemProcessor caller-owned processing function for one queued item
     */
    public SerialQueue(
            int capacity,
            int maxItemsPerRun,
            SerialLane lane,
            Consumer<T> itemProcessor) {

        failIfInvalidArgument(capacity >= 1, "capacity must be positive");
        failIfInvalidArgument(maxItemsPerRun >= 1, "maxItemsPerRun must be positive");
        failIfInvalidArgument(lane != null, "lane must not be null");
        failIfInvalidArgument(itemProcessor != null, "itemProcessor must not be null");

        this.queue = new ArrayBlockingQueue<T>(capacity);
        this.maxItemsPerRun = maxItemsPerRun;
        this.lane = lane;
        this.itemProcessor = itemProcessor;
    }

    /** Starts accepting items after the owning lane has started. */
    public synchronized void start() {
        failIfIllegalState(!accepting, "SerialQueue is already running");
        failIfIllegalState(lane.isRunning(), "SerialQueue lane must be running before start");
        accepting = true;
    }

    /**
     * Attempts to queue one item without blocking for capacity.
     *
     * <p>The first item queued while idle schedules the one fixed worker. Further
     * items only extend the item queue until that worker finishes its current
     * bounded batch.</p>
     */
    public synchronized OfferResult offer(T item) {
        failIfInvalidArgument(item != null, "item must not be null");

        if (!accepting || !lane.isRunning()) {
            return OfferResult.NOT_RUNNING;
        }
        if (!queue.offer(item)) {
            return OfferResult.FULL;
        }

        if (!workerScheduled) {
            workerScheduled = true;
            try {
                lane.execute(worker);
            } catch (RejectedExecutionException ex) {
                workerScheduled = false;
                accepting = false;
                queue.clear();
                notifyAll();
                throw ex;
            }
        }

        return OfferResult.ACCEPTED;
    }

    /**
     * Stops new admission and waits until every already accepted item has been
     * handled while the owning lane is still running.
     */
    @Override
    public synchronized void close() {
        accepting = false;

        if (!queue.isEmpty() && !workerScheduled && lane.isRunning()) {
            workerScheduled = true;
            try {
                lane.execute(worker);
            } catch (RejectedExecutionException ex) {
                workerScheduled = false;
                queue.clear();
                notifyAll();
            }
        }

        boolean interrupted = false;
        while ((workerScheduled || !queue.isEmpty()) && lane.isRunning()) {
            try {
                wait(100L);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }

        if (!queue.isEmpty()) {
            queue.clear();
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads queue capacity only after validating the supplied lane. */
    private static int capacity(SerialLane lane) {
        failIfInvalidArgument(lane != null, "lane must not be null");
        return lane.capacity();
    }

    /** Reads worker batch size only after validating the supplied lane. */
    private static int maxItemsPerRun(SerialLane lane) {
        failIfInvalidArgument(lane != null, "lane must not be null");
        return lane.maxItemsPerRun();
    }

    /** Returns the current number of caller-owned items waiting in this queue. */
    public int size() {
        return queue.size();
    }

    /**
     * Runs one bounded batch and re-admits the same worker exactly once when
     * items remain afterwards.
     */
    private void runWorker() {
        final int itemCount;
        synchronized (this) {
            itemCount = Math.min(queue.size(), maxItemsPerRun);
        }

        try {
            for (int index = 0; index < itemCount; index++) {
                T item = queue.poll();
                if (item == null) {
                    break;
                }
                itemProcessor.accept(item);
            }
        } finally {
            synchronized (this) {
                if (queue.isEmpty()) {
                    workerScheduled = false;
                    notifyAll();
                } else if (lane.isRunning()) {
                    try {
                        lane.execute(worker);
                    } catch (RejectedExecutionException ex) {
                        workerScheduled = false;
                        accepting = false;
                        queue.clear();
                        notifyAll();
                        throw ex;
                    }
                } else {
                    workerScheduled = false;
                    notifyAll();
                }
            }
        }
    }
}
