package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Complete running TimingNode/PresentationGateway composition for presentation tests.
 *
 * <p>This keeps test convenience out of the production PresentationGateway API.</p>
 */
public final class PresentationGatewayFixture implements AutoCloseable {
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    private final TimingNode node;
    private final ConfigurationControl configuration;
    private final PresentationGateway handler;

    public PresentationGatewayFixture(BuildIdentity identity) {
        node = TimingNodeFixture.create(
                new NodeId("TN-01"),
                new MemoryPersistence(),
                () -> RECORDED_AT);
        configuration = configurationControl(new NodeId("TN-01"));
        handler = new PresentationGateway(
                identity,
                node,
                configuration);
        node.start();
    }

    public PresentationGateway handler() {
        return handler;
    }

    public ConfigurationControl configuration() {
        return configuration;
    }

    @Override
    public void close() {
        node.stop();
    }

    private static ConfigurationControl configurationControl(
            NodeId nodeId) {
        DynamicConfiguration<TagProcessingPolicy> value =
                DynamicConfiguration.create(
                        TagProcessingPolicy.defaults(),
                        candidate -> candidate != null,
                        (startup, candidate) ->
                                startup.observationQueueCapacity()
                                        == candidate.observationQueueCapacity());

        Map<NodeId, DynamicConfiguration<TagProcessingPolicy>> values =
                new LinkedHashMap<
                        NodeId,
                        DynamicConfiguration<TagProcessingPolicy>>();
        values.put(nodeId, value);
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
