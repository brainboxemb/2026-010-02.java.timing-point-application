package io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Concrete configuration tree for one running application.
 *
 * <p>Runtime/deployment configuration is resolved before this tree is created.
 * The tree describes the composed executable; generic value/update mechanics
 * remain Infrastructure concerns.</p>
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

            DynamicConfiguration<TagProcessingPolicy> tagProcessing =
                    DynamicConfiguration.create(
                            policy,
                            value -> value != null,
                            ApplicationConfiguration::runtimeCompatible);
            nodes.put(nodeId, new TimingNodeConfiguration(tagProcessing));
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
}
