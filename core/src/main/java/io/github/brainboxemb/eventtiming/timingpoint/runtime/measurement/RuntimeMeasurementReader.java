package io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutorMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.platform.metrics.RuntimeObservation;

/**
 * Engineering-only pull reader for runtime characterization.
 *
 * <p>This type is composed by the characterization harness. It is not exposed
 * through TimingNode, PresentationGateway, IF-03, console or remote shell.</p>
 */
public final class RuntimeMeasurementReader {
    private final SerialExecutorMetrics timingNodeLaneMetrics;
    private final TimingNodeMetrics timingNodeMetrics;
    private final TagProcessingMetrics tagProcessingMetrics;

    public RuntimeMeasurementReader(
            SerialExecutorMetrics timingNodeLaneMetrics,
            TimingNodeMetrics timingNodeMetrics,
            TagProcessingMetrics tagProcessingMetrics) {
        if (timingNodeLaneMetrics == null) {
            throw new IllegalArgumentException("timingNodeLaneMetrics must not be null");
        }
        if (timingNodeMetrics == null) {
            throw new IllegalArgumentException("timingNodeMetrics must not be null");
        }
        if (tagProcessingMetrics == null) {
            throw new IllegalArgumentException("tagProcessingMetrics must not be null");
        }
        this.timingNodeLaneMetrics = timingNodeLaneMetrics;
        this.timingNodeMetrics = timingNodeMetrics;
        this.tagProcessingMetrics = tagProcessingMetrics;
    }

    public TimingNodeRuntimeSnapshot timingNode() {
        return new TimingNodeRuntimeSnapshot(
                timingNodeLaneMetrics.snapshot(),
                timingNodeMetrics.snapshot());
    }

    public TagProcessingMetrics.Snapshot tagProcessing() {
        return tagProcessingMetrics.snapshot();
    }

    public JvmRuntimeSnapshot jvm() {
        return new JvmRuntimeSnapshot(
                RuntimeObservation.capture(),
                RuntimeObservation.threadCpuTimeNanos(
                        "tp-dml-",
                        "tp-apl-",
                        "tp-io-"));
    }
}
