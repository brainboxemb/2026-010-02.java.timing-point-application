package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

/**
 * Test-only complete TimingNode construction with explicit execution lanes.
 *
 * <p>Production code receives these lanes from runtime composition. Presentation
 * and processing tests use this fixture so they do not duplicate runtime
 * executor wiring or fall back to hidden component-owned thread creation.</p>
 */
public final class TimingNodeFixture {
    private TimingNodeFixture() {
    }

    public static TimingNode create(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimeSource timeSource) {
        return create(
                nodeId,
                persistence,
                new DefaultTimingDataFactory(),
                timeSource);
    }

    public static TimingNode create(
            NodeId nodeId,
            TimingDataPersistence persistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
        String suffix = nodeId == null ? "missing" : nodeId.value();
        return new TimingNode(
                nodeId,
                persistence,
                timingDataFactory,
                timeSource,
                ReadOnlyConfiguration.fixed(
                        TagProcessingPolicy.defaults()),
                tagId -> null,
                new SerialExecutor(
                        32,
                        "test-node-" + suffix),
                new SerialScheduledExecutor(
                        "test-tag-" + suffix));
    }
}
