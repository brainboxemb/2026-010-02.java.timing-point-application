package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessorMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

/**
 * Composes the child components owned by one {@link TimingNode}.
 *
 * <p>This class only builds one node. System-level concerns such as
 * AntennaManager, SystemConductor and routing between nodes remain in
 * {@link TimingSystemComposer}.</p>
 */
final class TimingNodeComposer {
    private final ApplicationConfiguration configuration;
    private final RuntimeExecutors executors;
    private final PlatformEnvironment platform;

    TimingNodeComposer(
            ApplicationConfiguration configuration,
            RuntimeExecutors executors,
            PlatformEnvironment platform) {
        this.configuration = configuration;
        this.executors = executors;
        this.platform = platform;
    }

    TimingNode compose(
            Config.TimingNodeConfig nodeConfig,
            TimingSystemResolvedData system,
            TimeSource timeSource) {
        RuntimeExecutors.TimingNodeLanes lanes =
                executors.createTimingNodeLanes();

        TimingDataPersistence persistence =
                new DefaultTimingDataPersistence(
                        new FileAppendOnlyRecordStore(
                                nodeConfig.timingDataPath()),
                        nodeConfig.timingNodeId(),
                        system.timingDataCodec());

        LogBookProcessor logBook =
                new LogBookProcessor(
                        nodeConfig.timingNodeId(),
                        persistence,
                        system.timingDataFactory(),
                        timeSource,
                        platform.monotonicClock(),
                        new LogBookProcessorMetrics(),
                        lanes.dataLane());

        NodeTaskProcessor tasks =
                new NodeTaskProcessor(
                        logBook,
                        lanes.taskLane(),
                        system.eventData(),
                        UpstreamStream.unavailable());

        TagProcessor tags =
                new TagProcessor(
                        system.eventData(),
                        configuration.timingNode(
                                nodeConfig.timingNodeId())
                                .tagProcessing(),
                        platform.monotonicClock(),
                        new TagProcessingMetrics(),
                        lanes.tagProcessor(),
                        lanes.tagProcessorScheduler());

        configuration.timingNode(
                nodeConfig.timingNodeId())
                .tagProcessing()
                .changes()
                .subscribe(
                        tags::onPolicyConfigurationChanged);

        return new TimingNode(
                logBook,
                tasks,
                tags);
    }
}
