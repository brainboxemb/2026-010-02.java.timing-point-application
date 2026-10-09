package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ConfigurationUpdateResult;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application use-case boundary for current configuration query and runtime
 * override requests.
 *
 * <p>Presentation depends on this control rather than on Runtime's concrete
 * ApplicationConfiguration tree. Runtime supplies the authoritative typed
 * configuration values during composition; this class owns the application
 * semantics for reading and changing them.</p>
 */
public final class ConfigurationControl {
    private static final Logger LOG =
            LoggerFactory.getLogger(ConfigurationControl.class);

    public enum UpdateResult {
        APPLIED,
        NO_CHANGE,
        INVALID,
        RESTART_REQUIRED
    }

    /** Partial replacement request for one TagProcessingPolicy. */
    public static final class TagProcessingPatch {
        private final Long quietTimeoutMillis;
        private final Long maxBurstDurationMillis;
        private final Long duplicateWindowMillis;
        private final Long sweepCadenceMillis;
        private final Long observationQueueCapacity;

        public TagProcessingPatch(
                Long quietTimeoutMillis,
                Long maxBurstDurationMillis,
                Long duplicateWindowMillis,
                Long sweepCadenceMillis,
                Long observationQueueCapacity) {
            this.quietTimeoutMillis = quietTimeoutMillis;
            this.maxBurstDurationMillis = maxBurstDurationMillis;
            this.duplicateWindowMillis = duplicateWindowMillis;
            this.sweepCadenceMillis = sweepCadenceMillis;
            this.observationQueueCapacity = observationQueueCapacity;
        }

        public Long quietTimeoutMillis() {
            return quietTimeoutMillis;
        }

        public Long maxBurstDurationMillis() {
            return maxBurstDurationMillis;
        }

        public Long duplicateWindowMillis() {
            return duplicateWindowMillis;
        }

        public Long sweepCadenceMillis() {
            return sweepCadenceMillis;
        }

        public Long observationQueueCapacity() {
            return observationQueueCapacity;
        }
    }

    /** Millisecond-oriented IF-03 value projection of an immutable policy. */
    public static final class TagProcessingValue {
        private final long quietTimeoutMillis;
        private final long maxBurstDurationMillis;
        private final long duplicateWindowMillis;
        private final long sweepCadenceMillis;
        private final int observationQueueCapacity;

        private TagProcessingValue(TagProcessingPolicy policy) {
            quietTimeoutMillis =
                    TimeUnit.NANOSECONDS.toMillis(
                            policy.quietTimeoutNanos());
            maxBurstDurationMillis =
                    TimeUnit.NANOSECONDS.toMillis(
                            policy.maxBurstDurationNanos());
            duplicateWindowMillis =
                    TimeUnit.NANOSECONDS.toMillis(
                            policy.duplicateWindowNanos());
            sweepCadenceMillis =
                    TimeUnit.NANOSECONDS.toMillis(
                            policy.sweepCadenceNanos());
            observationQueueCapacity =
                    policy.observationQueueCapacity();
        }

        public long quietTimeoutMillis() {
            return quietTimeoutMillis;
        }

        public long maxBurstDurationMillis() {
            return maxBurstDurationMillis;
        }

        public long duplicateWindowMillis() {
            return duplicateWindowMillis;
        }

        public long sweepCadenceMillis() {
            return sweepCadenceMillis;
        }

        public int observationQueueCapacity() {
            return observationQueueCapacity;
        }
    }

    /** Current presentation-safe view of one node's TagProcessing policy. */
    public static final class TagProcessingConfiguration {
        private final TagProcessingValue startup;
        private final TagProcessingValue current;
        private final boolean overridden;

        private TagProcessingConfiguration(
                TagProcessingPolicy startup,
                TagProcessingPolicy current,
                boolean overridden) {
            this.startup = new TagProcessingValue(startup);
            this.current = new TagProcessingValue(current);
            this.overridden = overridden;
        }

        public TagProcessingValue startup() {
            return startup;
        }

        public TagProcessingValue current() {
            return current;
        }

        public boolean overridden() {
            return overridden;
        }

        public boolean quietTimeoutRuntimeMutable() {
            return true;
        }

        public boolean maxBurstDurationRuntimeMutable() {
            return true;
        }

        public boolean duplicateWindowRuntimeMutable() {
            return true;
        }

        public boolean sweepCadenceRuntimeMutable() {
            return true;
        }

        public boolean observationQueueCapacityRuntimeMutable() {
            return false;
        }
    }

    public static final class TimingNodeConfiguration {
        private final NodeId nodeId;
        private final TagProcessingConfiguration tagProcessing;

        private TimingNodeConfiguration(
                NodeId nodeId,
                TagProcessingConfiguration tagProcessing) {
            this.nodeId = nodeId;
            this.tagProcessing = tagProcessing;
        }

        public NodeId nodeId() {
            return nodeId;
        }

        public TagProcessingConfiguration tagProcessing() {
            return tagProcessing;
        }
    }

    public static final class Snapshot {
        private final List<TimingNodeConfiguration> timingNodes;

        private Snapshot(List<TimingNodeConfiguration> timingNodes) {
            this.timingNodes = Collections.unmodifiableList(
                    new ArrayList<TimingNodeConfiguration>(timingNodes));
        }

        public List<TimingNodeConfiguration> timingNodes() {
            return timingNodes;
        }
    }

    /** Semantic outcome plus the authoritative post-request current view. */
    public static final class Update {
        private final UpdateResult result;
        private final TagProcessingConfiguration tagProcessing;

        private Update(
                UpdateResult result,
                TagProcessingConfiguration tagProcessing) {
            this.result = result;
            this.tagProcessing = tagProcessing;
        }

        public UpdateResult result() {
            return result;
        }

        public TagProcessingConfiguration tagProcessing() {
            return tagProcessing;
        }
    }

    /** Post-fact application event for one applied runtime configuration change. */
    public static final class Change {
        private final NodeId nodeId;
        private final TagProcessingConfiguration tagProcessing;

        private Change(
                NodeId nodeId,
                TagProcessingConfiguration tagProcessing) {
            this.nodeId = nodeId;
            this.tagProcessing = tagProcessing;
        }

        public NodeId nodeId() {
            return nodeId;
        }

        public TagProcessingConfiguration tagProcessing() {
            return tagProcessing;
        }
    }

    private final Map<NodeId, DynamicConfiguration<TagProcessingPolicy>>
            tagProcessingByNode;
    private final Event<Change> changes = new Event<Change>();

    public ConfigurationControl(
            Map<NodeId, DynamicConfiguration<TagProcessingPolicy>>
                    tagProcessingByNode) {
        if (tagProcessingByNode == null
                || tagProcessingByNode.isEmpty()) {
            throw new IllegalArgumentException(
                    "tagProcessingByNode must contain at least one TimingNode");
        }

        Map<NodeId, DynamicConfiguration<TagProcessingPolicy>> copy =
                new LinkedHashMap<NodeId, DynamicConfiguration<TagProcessingPolicy>>();
        for (Map.Entry<NodeId, DynamicConfiguration<TagProcessingPolicy>> entry
                : tagProcessingByNode.entrySet()) {
            NodeId nodeId = entry.getKey();
            DynamicConfiguration<TagProcessingPolicy> configuration =
                    entry.getValue();
            if (nodeId == null || configuration == null) {
                throw new IllegalArgumentException(
                        "tagProcessingByNode must not contain null keys or values");
            }
            copy.put(nodeId, configuration);
        }
        this.tagProcessingByNode =
                Collections.unmodifiableMap(copy);

        for (Map.Entry<NodeId, DynamicConfiguration<TagProcessingPolicy>> entry
                : this.tagProcessingByNode.entrySet()) {
            final NodeId nodeId = entry.getKey();
            final DynamicConfiguration<TagProcessingPolicy> configuration =
                    entry.getValue();
            configuration.changes().subscribe(
                    ignored -> publishChange(nodeId, configuration));
        }
    }

    public synchronized Snapshot snapshot() {
        List<TimingNodeConfiguration> nodes =
                new ArrayList<TimingNodeConfiguration>(
                        tagProcessingByNode.size());
        for (Map.Entry<NodeId, DynamicConfiguration<TagProcessingPolicy>> entry
                : tagProcessingByNode.entrySet()) {
            nodes.add(new TimingNodeConfiguration(
                    entry.getKey(),
                    view(entry.getValue())));
        }
        return new Snapshot(nodes);
    }

    public synchronized Update setTagProcessing(
            NodeId nodeId,
            TagProcessingPatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException(
                    "patch must not be null");
        }

        DynamicConfiguration<TagProcessingPolicy> configuration =
                requireNode(nodeId);
        final TagProcessingPolicy candidate;
        try {
            candidate =
                    candidate(
                            configuration.currentValue(),
                            patch);
        } catch (IllegalArgumentException ex) {
            return new Update(
                    UpdateResult.INVALID,
                    view(configuration));
        }

        ConfigurationUpdateResult result =
                configuration.override(candidate);
        return new Update(
                UpdateResult.valueOf(result.name()),
                view(configuration));
    }

    public synchronized Update clearTagProcessing(NodeId nodeId) {
        DynamicConfiguration<TagProcessingPolicy> configuration =
                requireNode(nodeId);
        ConfigurationUpdateResult result =
                configuration.clearOverride();
        return new Update(
                UpdateResult.valueOf(result.name()),
                view(configuration));
    }

    public EventSource<Change> changes() {
        return changes;
    }

    private DynamicConfiguration<TagProcessingPolicy> requireNode(
            NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException(
                    "nodeId must not be null");
        }
        DynamicConfiguration<TagProcessingPolicy> configuration =
                tagProcessingByNode.get(nodeId);
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingNode configuration "
                            + nodeId.value());
        }
        return configuration;
    }

    private static TagProcessingConfiguration view(
            DynamicConfiguration<TagProcessingPolicy> configuration) {
        return new TagProcessingConfiguration(
                configuration.startupValue(),
                configuration.currentValue(),
                configuration.overridden());
    }

    private static TagProcessingPolicy candidate(
            TagProcessingPolicy current,
            TagProcessingPatch patch) {
        int queueCapacity =
                queueCapacity(
                        current.observationQueueCapacity(),
                        patch.observationQueueCapacity());

        return new TagProcessingPolicy(
                duration(
                        current.quietTimeoutNanos(),
                        patch.quietTimeoutMillis()),
                duration(
                        current.maxBurstDurationNanos(),
                        patch.maxBurstDurationMillis()),
                duration(
                        current.duplicateWindowNanos(),
                        patch.duplicateWindowMillis()),
                duration(
                        current.sweepCadenceNanos(),
                        patch.sweepCadenceMillis()),
                queueCapacity);
    }

    private static Duration duration(
            long currentNanos,
            Long replacementMillis) {
        return replacementMillis == null
                ? Duration.ofNanos(currentNanos)
                : Duration.ofMillis(replacementMillis.longValue());
    }

    private static int queueCapacity(
            int current,
            Long replacement) {
        if (replacement == null) {
            return current;
        }
        long value = replacement.longValue();
        if (value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "observationQueueCapacity is outside integer range");
        }
        return (int) value;
    }

    private void publishChange(
            NodeId nodeId,
            DynamicConfiguration<TagProcessingPolicy> configuration) {
        Event.DeliveryReport report =
                changes.emit(
                        new Change(
                                nodeId,
                                view(configuration)));
        if (!report.successful()) {
            LOG.warn(
                    "ConfigurationControl change listener failure(s): {}",
                    report.failureCount());
        }
    }
}
