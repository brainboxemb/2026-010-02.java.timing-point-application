package io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneMetrics;

/**
 * Immutable engineering snapshot of one TimingNode runtime path.
 */
public final class TimingNodeRuntimeSnapshot {
    private final SerialLaneMetrics.Snapshot lane;
    private final LogBookMetrics.Snapshot logBook;

    /** Creates one immutable TimingNode runtime snapshot from lane and LogBook metrics. */
    TimingNodeRuntimeSnapshot(
            SerialLaneMetrics.Snapshot lane,
            LogBookMetrics.Snapshot logBook) {
        this.lane = lane;
        this.logBook = logBook;
    }

    /** Returns the current Data Lane queue depth. */
    public int queueDepth() {
        return lane.queueDepth();
    }
    /** Returns the largest observed Data Lane queue depth. */
    public int queueHighWaterMark() {
        return lane.queueHighWaterMark();
    }
    /** Returns how many Data Lane items were successfully queued. */
    public long queuedTaskCount() {
        return lane.queuedCount();
    }
    /** Returns how many Data Lane items were rejected because the queue was full. */
    public long queueFullCount() {
        return lane.fullCount();
    }
    /** Returns how many Data Lane items were rejected because the lane was not running. */
    public long queueNotRunningCount() {
        return lane.notRunningCount();
    }
    /** Returns how many Data Lane items completed execution. */
    public long queueCompletedCount() {
        return lane.completedCount();
    }
    /** Returns cumulative Data Lane queue-wait time. */
    public long totalQueueWaitNanos() {
        return lane.totalQueueWaitNanos();
    }
    /** Returns the longest observed Data Lane queue wait. */
    public long maxQueueWaitNanos() {
        return lane.maxQueueWaitNanos();
    }
    /** Returns cumulative Data Lane task execution time. */
    public long totalExecutionNanos() {
        return lane.totalExecutionNanos();
    }
    /** Returns the longest observed Data Lane task execution time. */
    public long maxExecutionNanos() {
        return lane.maxExecutionNanos();
    }

    /** Returns TimingData persistence append attempts. */
    public long timingDataAppendAttempts() {
        return logBook.timingDataAppendAttempts();
    }
    /** Returns TimingData persistence append failures. */
    public long timingDataAppendFailures() {
        return logBook.timingDataAppendFailures();
    }
    /** Returns successfully committed TimingData records. */
    public long timingDataCommitCount() {
        return logBook.timingDataCommitCount();
    }
    /** Returns cumulative TimingData append duration. */
    public long totalTimingDataAppendNanos() {
        return logBook.totalTimingDataAppendNanos();
    }
    /** Returns the longest observed TimingData append duration. */
    public long maxTimingDataAppendNanos() {
        return logBook.maxTimingDataAppendNanos();
    }
    /** Returns TimingData event deliveries after commit. */
    public long timingDataEventDeliveries() {
        return logBook.timingDataEventDeliveries();
    }
    /** Returns TimingData event-listener failures. */
    public long timingDataEventListenerFailures() {
        return logBook.timingDataEventListenerFailures();
    }
    /** Returns cumulative TimingData event-delivery duration. */
    public long totalTimingDataEventNanos() {
        return logBook.totalTimingDataEventNanos();
    }
    /** Returns the longest observed TimingData event-delivery duration. */
    public long maxTimingDataEventNanos() {
        return logBook.maxTimingDataEventNanos();
    }
}
