package io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement;

import io.github.brainboxemb.eventtiming.timingpoint.domain.logBook.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneMetrics;

/**
 * Immutable engineering snapshot of one TimingNode runtime path.
 */
public final class TimingNodeRuntimeSnapshot {
    private final SerialLaneMetrics.Snapshot lane;
    private final LogBookMetrics.Snapshot logBook;

    TimingNodeRuntimeSnapshot(
            SerialLaneMetrics.Snapshot lane,
            LogBookMetrics.Snapshot logBook) {
        this.lane = lane;
        this.logBook = logBook;
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

    public long timingDataAppendAttempts() { return logBook.timingDataAppendAttempts(); }
    public long timingDataAppendFailures() { return logBook.timingDataAppendFailures(); }
    public long timingDataCommitCount() { return logBook.timingDataCommitCount(); }
    public long totalTimingDataAppendNanos() { return logBook.totalTimingDataAppendNanos(); }
    public long maxTimingDataAppendNanos() { return logBook.maxTimingDataAppendNanos(); }
    public long timingDataEventDeliveries() { return logBook.timingDataEventDeliveries(); }
    public long timingDataEventListenerFailures() { return logBook.timingDataEventListenerFailures(); }
    public long totalTimingDataEventNanos() { return logBook.totalTimingDataEventNanos(); }
    public long maxTimingDataEventNanos() { return logBook.maxTimingDataEventNanos(); }
}
