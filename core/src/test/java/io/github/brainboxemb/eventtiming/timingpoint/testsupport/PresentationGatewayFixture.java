package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Complete running TimingNode/PresentationGateway composition for presentation tests.
 *
 * <p>This keeps test convenience out of the production PresentationGateway API.</p>
 */
public final class PresentationGatewayFixture implements AutoCloseable {
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    private final List<TimingNode> nodes;
    private final ConfigurationControl configuration;
    private final PresentationGateway handler;

    public PresentationGatewayFixture(BuildIdentity identity) {
        this(identity, "A");
    }

    public PresentationGatewayFixture(
            BuildIdentity identity,
            String... nodeIds) {
        if (nodeIds == null || nodeIds.length == 0) {
            throw new IllegalArgumentException(
                    "nodeIds must not be empty");
        }

        nodes = new ArrayList<TimingNode>();
        NodeId[] ids = new NodeId[nodeIds.length];
        TimingNodeList timingNodes = new TimingNodeList();
        for (int index = 0; index < nodeIds.length; index++) {
            NodeId nodeId = new NodeId(nodeIds[index]);
            ids[index] = nodeId;
            TimingNode node = TimingNodeFixture.create(
                    nodeId,
                    new MemoryPersistence(),
                    () -> RECORDED_AT.instant());
            nodes.add(node);
            timingNodes.add(node);
        }

        configuration = configurationControl(ids);
        handler = new PresentationGateway(
                identity,
                timingNodes,
                configuration);
        for (TimingNode node : nodes) {
            node.activate();
        }
    }

    public PresentationGateway handler() {
        return handler;
    }

    public ConfigurationControl configuration() {
        return configuration;
    }

    @Override
    public void close() {
        for (TimingNode node : nodes) {
            node.deactivate();
        }
    }

    public static ConfigurationControl configurationControl(
            NodeId... nodeIds) {
        if (nodeIds == null || nodeIds.length == 0) {
            throw new IllegalArgumentException(
                    "nodeIds must not be empty");
        }

        Map<NodeId, DynamicConfiguration<TagProcessingPolicy>> values =
                new LinkedHashMap<
                        NodeId,
                        DynamicConfiguration<TagProcessingPolicy>>();
        for (NodeId nodeId : nodeIds) {
            if (nodeId == null) {
                throw new IllegalArgumentException(
                        "nodeIds must not contain null");
            }
            DynamicConfiguration<TagProcessingPolicy> value =
                    DynamicConfiguration.create(
                            TagProcessingPolicy.defaults(),
                            candidate -> candidate != null,
                            (startup, candidate) ->
                                    startup.observationQueueCapacity()
                                            == candidate.observationQueueCapacity());
            values.put(nodeId, value);
        }
        return new ConfigurationControl(values);
    }

    private static final class MemoryPersistence implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // Presentation tests do not exercise durable persistence.
        }
    }
}
