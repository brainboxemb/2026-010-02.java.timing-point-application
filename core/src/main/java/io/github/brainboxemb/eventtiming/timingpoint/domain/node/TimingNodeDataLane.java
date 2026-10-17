package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookOperationItem;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneMetrics;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * One TimingNode Data Lane with a typed shared FIFO.
 *
 * <p>LogBook operations and upstream turns are different item variants in the
 * same lane, so their ordering is explicit without queueing Runnable work.</p>
 */
public final class TimingNodeDataLane implements AutoCloseable {

    /** One upstream protocol/replay turn in the shared Data Lane. */
    private enum UpstreamTurn implements TimingNodeDataItem {
        INSTANCE
    }

    private final SerialLane<TimingNodeDataItem> lane;

    private Consumer<LogBookOperationItem<?>> logBookProcessor;
    private Runnable upstreamTurnProcessor;

    public TimingNodeDataLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            Executor executor) {

        lane = new SerialLane<TimingNodeDataItem>(
                capacity,
                maxItemsPerRun,
                laneName,
                executor,
                this::processItem);
    }

    /** Binds the LogBook-owned operation processor during composition. */
    public synchronized void bindLogBook(
            Consumer<LogBookOperationItem<?>> processor) {

        failIfInvalidArgument(processor != null, "processor must not be null");
        failIfIllegalState(logBookProcessor == null, "LogBook Data Lane processor is already bound");
        logBookProcessor = processor;
    }

    /** Binds the node-scoped upstream-turn processor during composition. */
    public synchronized void bindUpstream(Runnable processor) {

        failIfInvalidArgument(processor != null, "processor must not be null");
        failIfIllegalState(upstreamTurnProcessor == null, "Upstream Data Lane processor is already bound");
        upstreamTurnProcessor = processor;
    }

    /**
     * Starts this logical Data Lane with the supplied LogBook startup operation
     * as its first admitted item.
     */
    public synchronized SerialLane.OfferResult start(
            LogBookOperationItem<?> startupOperation) {

        failIfInvalidArgument(startupOperation != null, "startupOperation must not be null");
        lane.start();
        return lane.offer(startupOperation);
    }

    /** Queues one LogBook operation item. */
    public synchronized SerialLane.OfferResult offer(
            LogBookOperationItem<?> operation) {

        failIfInvalidArgument(operation != null, "operation must not be null");
        return lane.offer(operation);
    }

    /** Queues one upstream turn in the same Data Lane FIFO. */
    public synchronized SerialLane.OfferResult offerUpstreamTurn() {
        return lane.offer(UpstreamTurn.INSTANCE);
    }

    /** True only while the current Java thread owns this Data Lane. */
    public boolean isExecutingOnCurrentThread() {
        return lane.isExecutingOnCurrentThread();
    }

    public boolean isRunning() {
        return lane.isRunning();
    }

    public SerialLane.State state() {
        return lane.state();
    }

    public Throwable failure() {
        return lane.failure();
    }

    public SerialLaneMetrics metrics() {
        return lane.metrics();
    }

    @Override
    public void close() {
        lane.close();
    }

    private void processItem(TimingNodeDataItem item) {
        if (item instanceof LogBookOperationItem<?>) {
            Consumer<LogBookOperationItem<?>> processor;
            synchronized (this) {
                processor = logBookProcessor;
            }
            failIfIllegalState(processor != null, "LogBook Data Lane processor is not bound");
            processor.accept((LogBookOperationItem<?>) item);
            return;
        }

        if (item == UpstreamTurn.INSTANCE) {
            Runnable processor;
            synchronized (this) {
                processor = upstreamTurnProcessor;
            }
            failIfIllegalState(processor != null, "Upstream Data Lane processor is not bound");
            processor.run();
            return;
        }

        throw new IllegalStateException(
                "Unsupported TimingNode Data Lane item " + item.getClass().getName());
    }
}
