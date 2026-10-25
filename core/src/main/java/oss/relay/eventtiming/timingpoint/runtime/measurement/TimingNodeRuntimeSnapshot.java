package oss.relay.eventtiming.timingpoint.runtime.measurement;

import oss.relay.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import oss.relay.eventtiming.timingpoint.platform.execution.ExecutionMetrics;

/**
 * Immutable engineering snapshot of one TimingNode runtime path.
 */
public final class TimingNodeRuntimeSnapshot {
    private final ExecutionMetrics.Snapshot lane;
    private final LogBookMetrics.Snapshot logBook;

    /** Creates one immutable TimingNode runtime snapshot from lane and LogBook metrics. */
    TimingNodeRuntimeSnapshot(
            ExecutionMetrics.Snapshot lane,
            LogBookMetrics.Snapshot logBook) {
        this.lane = lane;
        this.logBook = logBook;
    }

    /** Returns the current LogBook LogBook Data Lane queue depth. */
    public int queueDepth() {
        return lane.queueDepth();
    }
    /** Returns the largest observed LogBook Data Lane queue depth. */
    public int queueHighWaterMark() {
        return lane.queueHighWaterMark();
    }
    /** Returns how many LogBook Data Lane items were successfully queued. */
    public long queuedItemCount() {
        return lane.queuedCount();
    }
    /** Returns how many LogBook Data Lane items were rejected because the queue was full. */
    public long queueFullCount() {
        return lane.fullCount();
    }
    /** Returns how many LogBook Data Lane items were rejected because the lane was not running. */
    public long queueNotRunningCount() {
        return lane.notRunningCount();
    }
    /** Returns how many LogBook Data Lane items completed execution. */
    public long queueCompletedCount() {
        return lane.completedCount();
    }
    /** Returns cumulative LogBook Data Lane queue-wait time. */
    public long totalQueueWaitNanos() {
        return lane.totalQueueWaitNanos();
    }
    /** Returns the longest observed LogBook Data Lane queue wait. */
    public long maxQueueWaitNanos() {
        return lane.maxQueueWaitNanos();
    }
    /** Returns cumulative LogBook Data Lane item execution time. */
    public long totalExecutionNanos() {
        return lane.totalExecutionNanos();
    }
    /** Returns the longest observed LogBook Data Lane item execution time. */
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
