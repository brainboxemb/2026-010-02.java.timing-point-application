package io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement;

import io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservation;

/**
 * Immutable engineering snapshot of process/JVM runtime observations.
 */
public final class JvmRuntimeSnapshot {
    private final RuntimeObservation.Snapshot runtime;
    private final long roleWorkerCpuTimeNanos;

    JvmRuntimeSnapshot(
            RuntimeObservation.Snapshot runtime,
            long roleWorkerCpuTimeNanos) {
        this.runtime = runtime;
        this.roleWorkerCpuTimeNanos = roleWorkerCpuTimeNanos;
    }

    public long heapUsedBytes() { return runtime.heapUsedBytes(); }
    public int liveThreadCount() { return runtime.liveThreadCount(); }
    public long gcCollectionCount() { return runtime.gcCollectionCount(); }
    public long gcCollectionTimeMillis() { return runtime.gcCollectionTimeMillis(); }

    /**
     * Aggregate CPU time of the shared Domain/Application/I/O role workers, or
     * {@code -1} when the selected JVM does not expose enabled thread CPU time.
     */
    public long roleWorkerCpuTimeNanos() {
        return roleWorkerCpuTimeNanos;
    }
}
