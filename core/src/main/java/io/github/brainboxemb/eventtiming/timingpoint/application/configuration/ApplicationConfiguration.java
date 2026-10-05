package io.github.brainboxemb.eventtiming.timingpoint.application.configuration;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative typed configuration root for one running application.
 *
 * <p>Runtime/deployment configuration is resolved before this object is created.
 * Components receive the narrow read-only configuration view they need, while
 * application-control code retains this central typed root for runtime changes.</p>
 */
public final class ApplicationConfiguration {
    private final Map<NodeId, TimingNodeConfiguration> timingNodes;

    public ApplicationConfiguration(
            Map<NodeId, TagProcessingPolicy> tagProcessingStartupPolicies) {
        if (tagProcessingStartupPolicies == null
                || tagProcessingStartupPolicies.isEmpty()) {
            throw new IllegalArgumentException(
                    "tagProcessingStartupPolicies must contain at least one TimingNode");
        }

        Map<NodeId, TimingNodeConfiguration> nodes = new LinkedHashMap<>();
        for (Map.Entry<NodeId, TagProcessingPolicy> entry
                : tagProcessingStartupPolicies.entrySet()) {
            NodeId nodeId = entry.getKey();
            TagProcessingPolicy policy = entry.getValue();
            if (nodeId == null) {
                throw new IllegalArgumentException(
                        "tagProcessingStartupPolicies must not contain a null TimingNode id");
            }
            if (policy == null) {
                throw new IllegalArgumentException(
                        "TagProcessingPolicy must not be null for " + nodeId.value());
            }
            nodes.put(
                    nodeId,
                    new TimingNodeConfiguration(
                            new DefaultDynamicConfiguration<>(
                                    policy,
                                    value -> value != null,
                                    ApplicationConfiguration::runtimeCompatible)));
        }
        timingNodes = Collections.unmodifiableMap(nodes);
    }

    public static ApplicationConfiguration singleTimingNode(
            NodeId nodeId,
            TagProcessingPolicy startupPolicy) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        Map<NodeId, TagProcessingPolicy> policies = new LinkedHashMap<>();
        policies.put(nodeId, startupPolicy);
        return new ApplicationConfiguration(policies);
    }

    public Set<NodeId> timingNodeIds() {
        return timingNodes.keySet();
    }

    public TimingNodeConfiguration timingNode(NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        TimingNodeConfiguration node = timingNodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingNode configuration " + nodeId.value());
        }
        return node;
    }

    private static boolean runtimeCompatible(
            TagProcessingPolicy startup,
            TagProcessingPolicy candidate) {
        return startup.observationQueueCapacity()
                == candidate.observationQueueCapacity();
    }

    /** Typed configuration owned by one TimingNode. */
    public static final class TimingNodeConfiguration {
        private final DynamicConfiguration<TagProcessingPolicy> tagProcessing;

        private TimingNodeConfiguration(
                DynamicConfiguration<TagProcessingPolicy> tagProcessing) {
            this.tagProcessing = tagProcessing;
        }

        /**
         * Returns the application-control view. Components should depend on this
         * value only through the ReadOnlyConfiguration super-interface.
         */
        public DynamicConfiguration<TagProcessingPolicy> tagProcessing() {
            return tagProcessing;
        }
    }
}
