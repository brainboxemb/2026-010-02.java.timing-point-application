package io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutorMetrics;

/**
 * Immutable engineering snapshot of one TimingNode runtime path.
 */
public final class TimingNodeRuntimeSnapshot {
    private final SerialExecutorMetrics.Snapshot lane;
    private final TimingNodeMetrics.Snapshot node;

    TimingNodeRuntimeSnapshot(
            SerialExecutorMetrics.Snapshot lane,
            TimingNodeMetrics.Snapshot node) {
        this.lane = lane;
        this.node = node;
    }

    public int queueDepth() { return lane.queueDepth(); }
    public int queueHighWaterMark() { return lane.queueHighWaterMark(); }
    public long queueAcceptedCount() { return lane.acceptedCount(); }
    public long queueFullCount() { return lane.fullCount(); }
    public long queueNotRunningCount() { return lane.notRunningCount(); }
    public long queueCompletedCount() { return lane.completedCount(); }
    public long totalQueueWaitNanos() { return lane.totalQueueWaitNanos(); }
    public long maxQueueWaitNanos() { return lane.maxQueueWaitNanos(); }
    public long totalExecutionNanos() { return lane.totalExecutionNanos(); }
    public long maxExecutionNanos() { return lane.maxExecutionNanos(); }

    public long timingDataAppendAttempts() { return node.timingDataAppendAttempts(); }
    public long timingDataAppendFailures() { return node.timingDataAppendFailures(); }
    public long timingDataCommitCount() { return node.timingDataCommitCount(); }
    public long totalTimingDataAppendNanos() { return node.totalTimingDataAppendNanos(); }
    public long maxTimingDataAppendNanos() { return node.maxTimingDataAppendNanos(); }
    public long timingDataEventDeliveries() { return node.timingDataEventDeliveries(); }
    public long timingDataEventListenerFailures() { return node.timingDataEventListenerFailures(); }
    public long totalTimingDataEventNanos() { return node.totalTimingDataEventNanos(); }
    public long maxTimingDataEventNanos() { return node.maxTimingDataEventNanos(); }
}
