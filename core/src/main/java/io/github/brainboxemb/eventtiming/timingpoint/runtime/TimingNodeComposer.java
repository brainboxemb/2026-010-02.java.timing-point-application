package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;

/**
 * Composes the child components owned by one {@link TimingNode}.
 *
 * <p>This class only builds one node. System-level construction such as
 * AntennaManager and TimingSystemConductor remains in {@link TimingSystemComposer};
 * the resulting TimingSystem owns system-level wiring and lifecycle.</p>
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

    /** Builds one complete TimingNode from resolved runtime configuration. */
    TimingNode compose(
            Config.TimingNodeConfig nodeConfig,
            AntennaId antennaId,
            TimingSystemResolvedData system,
            TimeSource timeSource) {

        RuntimeExecutors.TimingNodeExecutionResources execution =
                executors.createTimingNodeExecutionResources();
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
                        execution.logBookDataLaneExecutor(),
                        RuntimeExecutors.LOGBOOK_DATA_LANE_QUEUE_CAPACITY,
                        RuntimeExecutors.LOGBOOK_DATA_LANE_MAX_ITEMS_PER_RUN,
                        LogBook.DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                        LogBook.DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS);

        RaceDataState raceData = new RaceDataState(system.eventData());
        ActiveRegistrations activeRegistrations = new ActiveRegistrations();

        NodeService nodeService =
                new NodeService(
                        nodeConfig.timingNodeId(),
                        execution.taskRunner(),
                        logBook,
                        timeSource,
                        raceData,
                        activeRegistrations);

        TagProcessor tagProcessor =
                new TagProcessor(
                        system.eventData(),
                        tagConfiguration,
                        monotonicClock,
                        new TagProcessingMetrics(),
                        execution.tagProcessorScheduler());

        return new TimingNode(
                antennaId,
                timeSource,
                logBook,
                nodeService,
                tagProcessor,
                execution.taskRunner());
    }
}
