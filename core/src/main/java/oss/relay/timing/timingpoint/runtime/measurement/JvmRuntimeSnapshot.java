package oss.relay.timing.timingpoint.runtime.measurement;

import oss.relay.timing.timingpoint.platform.metrics.RuntimeObservation;

/**
 * Immutable engineering snapshot of process/JVM runtime observations.
 */
public final class JvmRuntimeSnapshot {
    private final RuntimeObservation.Snapshot runtime;
    private final long roleWorkerCpuTimeNanos;

    /** Creates one immutable JVM/runtime engineering snapshot. */
    JvmRuntimeSnapshot(
            RuntimeObservation.Snapshot runtime,
            long roleWorkerCpuTimeNanos) {
        this.runtime = runtime;
        this.roleWorkerCpuTimeNanos = roleWorkerCpuTimeNanos;
    }

    /** Returns currently used JVM heap bytes. */
    public long heapUsedBytes() {
        return runtime.heapUsedBytes();
    }
    /** Returns the current live JVM thread count. */
    public int liveThreadCount() {
        return runtime.liveThreadCount();
    }
    /** Returns aggregate garbage-collection count, or -1 when unavailable. */
    public long gcCollectionCount() {
        return runtime.gcCollectionCount();
    }
    /** Returns aggregate garbage-collection time in milliseconds, or -1 when unavailable. */
    public long gcCollectionTimeMillis() {
        return runtime.gcCollectionTimeMillis();
    }

    /**
     * Aggregate CPU time of the shared Domain/Application/I/O role workers, or
     * {@code -1} when the selected JVM does not expose enabled thread CPU time.
     */
    public long roleWorkerCpuTimeNanos() {
        return roleWorkerCpuTimeNanos;
    }
}
