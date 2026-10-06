package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Test-only complete TimingNode construction with explicit execution lanes.
 *
 * <p>Production code receives these lanes from runtime composition. Presentation
 * and processing tests use this fixture so they do not duplicate runtime
 * executor wiring or fall back to hidden component-owned thread creation.</p>
 */
public final class TimingNodeFixture {
    /*
     * Test-suite-owned workers. They are daemon threads so a failed test cannot
     * keep the JVM alive; individual logical lanes still close with their node.
     */
    private static final ExecutorService NODE_WORKER =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "test-node-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    private static final ScheduledThreadPoolExecutor TAG_WORKER =
            new ScheduledThreadPoolExecutor(
                    1,
                    runnable -> {
                        Thread thread = new Thread(
                                runnable,
                                "test-tag-worker");
                        thread.setDaemon(true);
                        return thread;
                    });

    static {
        TAG_WORKER.setRemoveOnCancelPolicy(true);
    }

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
                EventData.empty(),
                new SerialExecutor(
                        32,
                        "test-node-" + suffix,
                        NODE_WORKER),
                new SerialScheduledExecutor(
                        32,
                        "test-tag-" + suffix,
                        TAG_WORKER),
                SystemMonotonicClock.INSTANCE);
    }
}
