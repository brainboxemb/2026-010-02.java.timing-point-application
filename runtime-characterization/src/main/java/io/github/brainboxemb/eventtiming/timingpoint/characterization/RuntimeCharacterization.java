package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.JvmRuntimeSnapshot;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.RuntimeMeasurementReader;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.TimingNodeRuntimeSnapshot;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Sequences one engineering characterization repetition and retained evidence.
 *
 * <p>The product composition/workload mechanics live in
 * {@link CharacterizationRunContext}; this class keeps the measured workflow
 * visible in one short top-down method.</p>
 */
final class RuntimeCharacterization {
    private final HarnessBuildIdentity build =
            HarnessBuildIdentity.load();
    private final CharacterizationEvidenceWriter evidenceWriter =
            new CharacterizationEvidenceWriter();

    Path runOne(
            CharacterizationOptions options,
            int repetition)
            throws Exception {
        Instant startedAt =
                Instant.now();
        CharacterizationRunContext context =
                new CharacterizationRunContext(
                        options,
                        repetition);

        try {
            context.start();
            context.preloadHistory();
            context.runWarmup();

            RuntimeMeasurementReader measurements =
                    context.measurements();
            TimingNodeRuntimeSnapshot nodeBefore =
                    measurements.timingNode();
            TagProcessingMetrics.Snapshot tagBefore =
                    measurements.tagProcessing();
            JvmRuntimeSnapshot jvmBefore =
                    measurements.jvm();

            long measuredStartedNanos =
                    System.nanoTime();
            context.runMeasured();
            long measuredDurationNanos =
                    elapsedNanos(
                            measuredStartedNanos,
                            System.nanoTime());

            TimingNodeRuntimeSnapshot nodeAfter =
                    measurements.timingNode();
            TagProcessingMetrics.Snapshot tagAfter =
                    measurements.tagProcessing();
            JvmRuntimeSnapshot jvmAfter =
                    measurements.jvm();

            long queryStartedNanos =
                    System.nanoTime();
            int finalCount =
                    context.timingNode()
                            .query(
                                    TimingNodeQueries.visitLatestTimingData(
                                            Math.min(
                                                    100,
                                                    Math.max(
                                                            1,
                                                            options.preloadedHistory()
                                                                    + options.warmupObservations()
                                                                    + options.observations())),
                                            ignored -> {
                                                // Bounded no-op visitor measures traversal only.
                                            }));
            long historyQueryNanos =
                    elapsedNanos(
                            queryStartedNanos,
                            System.nanoTime());

            CharacterizationRunResult result =
                    new CharacterizationRunResult(
                            options,
                            build,
                            repetition,
                            startedAt,
                            Instant.now(),
                            measuredDurationNanos,
                            nodeBefore,
                            nodeAfter,
                            tagBefore,
                            tagAfter,
                            jvmBefore,
                            jvmAfter,
                            historyQueryNanos,
                            finalCount);

            return evidenceWriter.write(
                    result);
        } catch (Throwable failure) {
            evidenceWriter.writeFailure(
                    options,
                    build,
                    repetition,
                    startedAt,
                    failure);
            if (failure instanceof Exception) {
                throw (Exception) failure;
            }
            throw (Error) failure;
        } finally {
            context.close();
        }
    }

    private static long elapsedNanos(
            long started,
            long finished) {
        long elapsed =
                finished - started;
        return elapsed < 0L ? 0L : elapsed;
    }
}
