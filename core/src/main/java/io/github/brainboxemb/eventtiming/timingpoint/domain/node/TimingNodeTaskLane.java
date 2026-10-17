package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeServiceTaskItem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamTaskItem;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneMetrics;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * One TimingNode Task Lane with a typed shared FIFO.
 *
 * <p>NodeService state/query items and upstream protocol items share this lane
 * so their ordering is explicit without queueing Runnable or Callable work.</p>
 */
public final class TimingNodeTaskLane implements AutoCloseable {
    private final SerialLane<TimingNodeTaskItem> lane;

    private Consumer<NodeServiceTaskItem> nodeServiceProcessor;
    private Consumer<UpstreamTaskItem> upstreamProcessor;

    public TimingNodeTaskLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            Executor executor) {

        lane = new SerialLane<TimingNodeTaskItem>(
                capacity,
                maxItemsPerRun,
                laneName,
                executor,
                this::processItem);
    }

    /** Binds the NodeService-owned item processor during composition. */
    public synchronized void bindNodeService(
            Consumer<NodeServiceTaskItem> processor) {

        failIfInvalidArgument(processor != null, "processor must not be null");
        failIfIllegalState(
                nodeServiceProcessor == null,
                "NodeService Task Lane processor is already bound");
        nodeServiceProcessor = processor;
    }

    /** Binds the upstream-owned item processor during composition. */
    public synchronized void bindUpstream(
            Consumer<UpstreamTaskItem> processor) {

        failIfInvalidArgument(processor != null, "processor must not be null");
        failIfIllegalState(
                upstreamProcessor == null,
                "Upstream Task Lane processor is already bound");
        upstreamProcessor = processor;
    }

    public void start() {
        lane.start();
    }

    public SerialLane.OfferResult offer(NodeServiceTaskItem item) {
        failIfInvalidArgument(item != null, "item must not be null");
        return lane.offer(item);
    }

    public SerialLane.OfferResult offer(UpstreamTaskItem item) {
        failIfInvalidArgument(item != null, "item must not be null");
        return lane.offer(item);
    }

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

    private void processItem(TimingNodeTaskItem item) {
        if (item instanceof NodeServiceTaskItem) {
            Consumer<NodeServiceTaskItem> processor;
            synchronized (this) {
                processor = nodeServiceProcessor;
            }
            failIfIllegalState(
                    processor != null,
                    "NodeService Task Lane processor is not bound");
            processor.accept((NodeServiceTaskItem) item);
            return;
        }

        if (item instanceof UpstreamTaskItem) {
            Consumer<UpstreamTaskItem> processor;
            synchronized (this) {
                processor = upstreamProcessor;
            }
            failIfIllegalState(
                    processor != null,
                    "Upstream Task Lane processor is not bound");
            processor.accept((UpstreamTaskItem) item);
            return;
        }

        throw new IllegalStateException(
                "Unsupported TimingNode Task Lane item "
                        + item.getClass().getName());
    }
}
