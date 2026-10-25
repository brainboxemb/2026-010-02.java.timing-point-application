package oss.relay.eventtiming.timingpoint.runtime.measurement;

import oss.relay.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import oss.relay.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import oss.relay.eventtiming.timingpoint.platform.execution.ExecutionMetrics;
import oss.relay.eventtiming.timingpoint.platform.metrics.RuntimeObservation;

/**
 * Engineering-only pull reader for runtime characterization.
 *
 * <p>This type is composed by the characterization harness. It is not exposed
 * through TimingNode, PresentationGateway, IF-03, console or remote shell.</p>
 */
public final class RuntimeMeasurementReader {
    private final ExecutionMetrics logBookDataLaneMetrics;
    private final LogBookMetrics logBookMetrics;
    private final TagProcessingMetrics tagProcessingMetrics;

    /** Creates a pull reader over the supplied component-owned metric sources. */
    public RuntimeMeasurementReader(
            ExecutionMetrics logBookDataLaneMetrics,
            LogBookMetrics logBookMetrics,
            TagProcessingMetrics tagProcessingMetrics) {
        if (logBookDataLaneMetrics == null) {
            throw new IllegalArgumentException("logBookDataLaneMetrics must not be null");
        }
        if (logBookMetrics == null) {
            throw new IllegalArgumentException("logBookMetrics must not be null");
        }
        if (tagProcessingMetrics == null) {
            throw new IllegalArgumentException("tagProcessingMetrics must not be null");
        }
        this.logBookDataLaneMetrics = logBookDataLaneMetrics;
        this.logBookMetrics = logBookMetrics;
        this.tagProcessingMetrics = tagProcessingMetrics;
    }

    /** Returns one current LogBook Data Lane/LogBook engineering snapshot. */
    public TimingNodeRuntimeSnapshot timingNode() {
        return new TimingNodeRuntimeSnapshot(
                logBookDataLaneMetrics.snapshot(),
                logBookMetrics.snapshot());
    }

    /** Returns one current TagProcessor engineering snapshot. */
    public TagProcessingMetrics.Snapshot tagProcessing() {
        return tagProcessingMetrics.snapshot();
    }

    /** Captures one current JVM/process snapshot for the runtime role workers. */
    public JvmRuntimeSnapshot jvm() {
        return new JvmRuntimeSnapshot(
                RuntimeObservation.capture(),
                RuntimeObservation.threadCpuTimeNanos(
                        "tp-dml-",
                        "tp-apl-",
                        "tp-io-"));
    }
}
