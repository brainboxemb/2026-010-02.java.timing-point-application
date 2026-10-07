package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.JvmRuntimeSnapshot;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.TimingNodeRuntimeSnapshot;

import java.time.Instant;

/** Immutable retained inputs/measurements for one harness repetition. */
final class CharacterizationRunResult {
    private final CharacterizationOptions options;
    private final HarnessBuildIdentity build;
    private final int repetition;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final long measuredDurationNanos;
    private final TimingNodeRuntimeSnapshot nodeBefore;
    private final TimingNodeRuntimeSnapshot nodeAfter;
    private final TagProcessingMetrics.Snapshot tagBefore;
    private final TagProcessingMetrics.Snapshot tagAfter;
    private final JvmRuntimeSnapshot jvmBefore;
    private final JvmRuntimeSnapshot jvmAfter;
    private final long historyQueryNanos;
    private final int finalTimingDataCount;

    CharacterizationRunResult(
            CharacterizationOptions options,
            HarnessBuildIdentity build,
            int repetition,
            Instant startedAt,
            Instant finishedAt,
            long measuredDurationNanos,
            TimingNodeRuntimeSnapshot nodeBefore,
            TimingNodeRuntimeSnapshot nodeAfter,
            TagProcessingMetrics.Snapshot tagBefore,
            TagProcessingMetrics.Snapshot tagAfter,
            JvmRuntimeSnapshot jvmBefore,
            JvmRuntimeSnapshot jvmAfter,
            long historyQueryNanos,
            int finalTimingDataCount) {
        this.options = options;
        this.build = build;
        this.repetition = repetition;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.measuredDurationNanos = measuredDurationNanos;
        this.nodeBefore = nodeBefore;
        this.nodeAfter = nodeAfter;
        this.tagBefore = tagBefore;
        this.tagAfter = tagAfter;
        this.jvmBefore = jvmBefore;
        this.jvmAfter = jvmAfter;
        this.historyQueryNanos = historyQueryNanos;
        this.finalTimingDataCount = finalTimingDataCount;
    }

    CharacterizationOptions options() { return options; }
    HarnessBuildIdentity build() { return build; }
    int repetition() { return repetition; }
    Instant startedAt() { return startedAt; }
    Instant finishedAt() { return finishedAt; }
    long measuredDurationNanos() { return measuredDurationNanos; }
    TimingNodeRuntimeSnapshot nodeBefore() { return nodeBefore; }
    TimingNodeRuntimeSnapshot nodeAfter() { return nodeAfter; }
    TagProcessingMetrics.Snapshot tagBefore() { return tagBefore; }
    TagProcessingMetrics.Snapshot tagAfter() { return tagAfter; }
    JvmRuntimeSnapshot jvmBefore() { return jvmBefore; }
    JvmRuntimeSnapshot jvmAfter() { return jvmAfter; }
    long historyQueryNanos() { return historyQueryNanos; }
    int finalTimingDataCount() { return finalTimingDataCount; }
}
