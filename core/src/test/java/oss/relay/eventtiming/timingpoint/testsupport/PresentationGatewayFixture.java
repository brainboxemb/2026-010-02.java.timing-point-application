package oss.relay.eventtiming.timingpoint.testsupport;

import oss.relay.eventtiming.eventdata.EventData;
import oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport;
import oss.relay.eventtiming.timingdata.TimingRecord;
import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingdata.TimingTimestamp;
import oss.relay.eventtiming.timingpoint.application.ConfigurationControl;
import oss.relay.eventtiming.timingpoint.application.PresentationGateway;
import oss.relay.eventtiming.timingpoint.application.TimingNodeProxyComponents;
import oss.relay.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNode;
import oss.relay.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.eventtiming.timingpoint.infra.BuildIdentity;

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
        this(identity, EventData.empty(), "A");
    }

    public PresentationGatewayFixture(
            BuildIdentity identity,
            String... nodeIds) {
        this(identity, EventData.empty(), nodeIds);
    }

    public PresentationGatewayFixture(
            BuildIdentity identity,
            EventData eventData,
            String... nodeIds) {
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (nodeIds == null || nodeIds.length == 0) {
            throw new IllegalArgumentException(
                    "nodeIds must not be empty");
        }

        nodes = new ArrayList<TimingNode>();
        List<TimingNodeProxyComponents> presentationNodes =
                new ArrayList<TimingNodeProxyComponents>();
        NodeId[] ids = new NodeId[nodeIds.length];
        for (int index = 0; index < nodeIds.length; index++) {
            NodeId nodeId = new NodeId(nodeIds[index]);
            ids[index] = nodeId;
            TimingNodeFixture.Components components =
                    TimingNodeFixture.createComponents(
                            nodeId,
                            new MemoryPersistence(),
                            () -> RECORDED_AT.instant(),
                            eventData);
            nodes.add(components.node());
            presentationNodes.add(
                    components.presentationComponents());
        }

        configuration = configurationControl(ids);
        handler = new PresentationGateway(
                identity,
                presentationNodes,
                configuration);
        for (TimingNode node : nodes) {
            LifecycleTestSupport.activate(node);
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
            LifecycleTestSupport.deactivate(node);
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
                    Collections.<TimingRecord>emptyList(),
                    false);
        }

        @Override
        public void append(TimingRecord record) {
            // Presentation tests do not exercise durable persistence.
        }
    }
}
