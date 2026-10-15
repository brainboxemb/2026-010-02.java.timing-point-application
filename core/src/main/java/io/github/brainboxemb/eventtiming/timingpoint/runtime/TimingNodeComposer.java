package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeServiceState;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;

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
    private final MonotonicClock monotonicClock;

    /** Creates the runtime composer with resolved configuration and execution resources. */
    TimingNodeComposer(
            ApplicationConfiguration configuration,
            RuntimeExecutors executors,
            MonotonicClock monotonicClock) {
        this.configuration = configuration;
        this.executors = executors;
        this.monotonicClock = monotonicClock;
    }

    /** Builds one complete TimingNode child-component set from resolved runtime configuration. */
    TimingNodeComponents compose(
            Config.TimingNodeConfig nodeConfig,
            TimingSystemResolvedData system,
            TimeSource timeSource) {
        RuntimeExecutors.TimingNodeLanes lanes =
                executors.createTimingNodeLanes();
        ReadOnlyConfiguration<TagProcessingPolicy> tagConfiguration =
                configuration.timingNode(
                        nodeConfig.timingNodeId())
                        .tagProcessing();

        TimingDataPersistence persistence =
                new DefaultTimingDataPersistence(
                        new FileAppendOnlyRecordStore(
                                nodeConfig.timingDataPath()),
                        nodeConfig.timingNodeId(),
                        system.timingDataCodec());

        LogBook logBook =
                new LogBook(
                        nodeConfig.timingNodeId(),
                        persistence,
                        system.timingDataFactory(),
                        timeSource,
                        monotonicClock,
                        new LogBookMetrics(),
                        lanes.dataLane(),
                        LogBook.DEFAULT_OPERATION_TIMEOUT_MILLIS);

        NodeServiceState nodeServiceState =
                new NodeServiceState(
                        system.eventData());

        NodeService nodeService =
                new NodeService(
                        logBook,
                        lanes.taskLane(),
                        nodeServiceState);

        TagProcessor tags =
                new TagProcessor(
                        system.eventData(),
                        tagConfiguration,
                        monotonicClock,
                        new TagProcessingMetrics(),
                        lanes.tagProcessor(),
                        lanes.tagProcessorScheduler());

        tagConfiguration.changes()
                .subscribe(
                        tags::onPolicyConfigurationChanged);

        TimingNode node =
                new TimingNode(
                        logBook,
                        nodeService,
                        tags);
        return new TimingNodeComponents(
                node,
                tags);
    }
}
