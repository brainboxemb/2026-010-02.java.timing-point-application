package oss.relay.timing.timingpoint.runtime;

import oss.relay.timing.timingpoint.domain.node.TimingNode;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBook;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookMetrics;
import oss.relay.timing.timingpoint.domain.node.logbook.persistence.DefaultTimingDataPersistence;
import oss.relay.timing.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.timing.timingpoint.domain.node.processing.TagProcessingMetrics;
import oss.relay.timing.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.timing.timingpoint.domain.node.processing.TagProcessor;
import oss.relay.timing.timingpoint.domain.node.racedata.RaceDataState;
import oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrations;
import oss.relay.timing.timingpoint.domain.node.service.NodeService;
import oss.relay.timing.timingpoint.io.devices.antenna.AntennaId;
import oss.relay.timing.timingpoint.io.storage.FileAppendOnlyRecordStore;
import oss.relay.timing.timingpoint.platform.environment.MonotonicClock;
import oss.relay.timing.timingpoint.platform.time.TimeSource;
import oss.relay.timing.timingpoint.runtime.config.Config;
import oss.relay.timing.timingpoint.runtime.configuration.ApplicationConfiguration;
import oss.relay.timing.timingpoint.infra.configuration.ReadOnlyConfiguration;

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
    TimingNodeComposition compose(
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
                        execution.nodeScheduler(),
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

        TimingNode node =
                new TimingNode(
                        antennaId,
                        timeSource,
                        logBook,
                        nodeService,
                        tagProcessor,
                        execution.nodeScheduler());

        return new TimingNodeComposition(
                node,
                nodeService,
                logBook);
    }
}
