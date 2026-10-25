package oss.relay.eventtiming.timingpoint.runtime;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.application.ApplicationConductor;
import oss.relay.eventtiming.timingpoint.application.ConfigurationControl;
import oss.relay.eventtiming.timingpoint.application.PresentationGateway;
import oss.relay.eventtiming.timingpoint.application.TimingNodeProxyComponents;
import oss.relay.eventtiming.timingpoint.domain.system.TimingSystem;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNode;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNodeList;
import oss.relay.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.eventtiming.timingpoint.infra.BuildIdentity;
import oss.relay.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import oss.relay.eventtiming.timingpoint.infra.logging.ConsolePromptControl;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import oss.relay.eventtiming.timingpoint.io.messaging.engineering.EngineerApiConnector;
import oss.relay.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import oss.relay.eventtiming.timingpoint.platform.time.TimeSource;
import oss.relay.eventtiming.timingpoint.runtime.config.Config;
import oss.relay.eventtiming.timingpoint.runtime.config.UpstreamConfig;
import oss.relay.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;
import oss.relay.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunner;

import java.io.Reader;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the complete runtime object graph from already resolved system data. */
final class TimingApplicationAssembler {
    private TimingApplicationAssembler() {
    }

    /** Builds the complete application after provider/data resolution has finished. */
    static TimingApplicationRuntime assemble(
            BuildIdentity buildIdentity,
            Config config,
            AntennaSet antennaSet,
            SimulatedAntenna simulatedAntenna,
            TimingSystemResolvedDataList resolvedSystems,
            PlatformEnvironment platform,
            LoggingLevelControl loggingLevelControl,
            ConsolePromptControl consolePromptControl,
            Reader consoleInput,
            Writer consoleOutput) {
        requireCompositionInput(
                buildIdentity,
                config,
                antennaSet,
                resolvedSystems,
                platform);

        ApplicationConfiguration applicationConfiguration =
                createApplicationConfiguration(
                        config);
        RuntimeExecutors executors =
                new RuntimeExecutors();

        try {
            RuntimeTimeSources runtimeTimeSources =
                    new RuntimeTimeSources(
                            platform);
            DynamicConfiguration<Duration> timingDataReplayPacketDelay =
                    DynamicConfiguration.create(
                            config.upstream().timingDataReplayPacketDelay(),
                            value -> value != null && !value.isNegative(),
                            (startup, candidate) -> true);
            List<TimingNode> timingNodes = new ArrayList<TimingNode>();
            List<TimingNodeProxyComponents> presentationNodes =
                    new ArrayList<TimingNodeProxyComponents>();
            List<TimingSystem> systems = new ArrayList<TimingSystem>();
            List<EngineerApiConnector> upstreamConnectors =
                    new ArrayList<EngineerApiConnector>();
            ApplicationConductor applicationConductor =
                    new ApplicationConductor();
            SimulatedTagScenarioRunner simulationRunner =
                    null;

            TimingSystemComposer systemComposer =
                    new TimingSystemComposer(
                            applicationConfiguration,
                            executors,
                            platform,
                            timingDataReplayPacketDelay);

            for (TimingSystemResolvedData resolvedSystem
                    : resolvedSystems) {
                TimeSource timeSource =
                        runtimeTimeSources.createTimeSource();
                EngineerApiConnector upstreamConnector = createEngineeringUpstreamConnector(config.upstream());
                TimingSystemComposition systemComposition =
                        systemComposer.compose(
                                resolvedSystem,
                                config.antennaManager(
                                        resolvedSystem
                                                .configuration()
                                                .timingSystemId()),
                                antennaSet,
                                timeSource,
                                upstreamConnector);
                TimingSystem system =
                        systemComposition.timingSystem();
                presentationNodes.addAll(
                        systemComposition.presentationNodes());
                upstreamConnectors.add(upstreamConnector);
                systems.add(system);
                applicationConductor.registerTimingSystem(system);
                for (TimingNode node : system.timingNodes()) {
                    timingNodes.add(node);
                }

                if (resolvedSystems.size() == 1
                        && system.timingNodes().size() == 1
                        && resolvedSystem
                                .tagScenarioSimulationEnabled()
                        && simulatedAntenna != null
                        && !resolvedSystem
                                .eventData()
                                .isEmpty()) {
                    simulationRunner =
                            new SimulatedTagScenarioRunner(
                                    simulatedAntenna,
                                    resolvedSystem.eventData(),
                                    timeSource,
                                    platform.monotonicClock(),
                                    executors.sharedIoScheduledExecutor());
                }
            }

            TimingNodeList allTimingNodes = new TimingNodeList(timingNodes);
            ConfigurationControl configurationControl =
                    createConfigurationControl(
                            applicationConfiguration,
                            timingDataReplayPacketDelay);
            PresentationGateway presentationGateway =
                    new PresentationGateway(
                            buildIdentity,
                            presentationNodes,
                            configurationControl,
                            simulationRunner);
            ApplicationExitSignal exitSignal =
                    new ApplicationExitSignal();
            PresentationRuntime presentation =
                    new PresentationRuntime(
                            config.presentation(),
                            presentationGateway,
                            upstreamConnectors,
                            loggingLevelControl,
                            consolePromptControl,
                            exitSignal::requestStop,
                            exitSignal::requestRestart,
                            exitSignal::requestRestartKeepLogging,
                            consoleInput,
                            consoleOutput);

            return new TimingApplicationRuntime(
                    buildIdentity,
                    allTimingNodes,
                    applicationConfiguration,
                    presentationGateway,
                    executors,
                    systems,
                    simulationRunner,
                    applicationConductor,
                    presentation,
                    exitSignal);
        } catch (RuntimeException | Error failure) {
            executors.close();
            throw failure;
        }
    }

    /** Resolves the configured upstream transport to the implementation available in this application build. */
    private static EngineerApiConnector createEngineeringUpstreamConnector(UpstreamConfig upstream) {
        switch (upstream.transport()) {
            case ENGINEERING_API:
                return new EngineerApiConnector();
            default:
                throw new IllegalStateException("Unsupported effective upstream transport " + upstream.transport());
        }
    }

    /** Builds mutable runtime configuration from immutable startup policy values. */
    private static ApplicationConfiguration createApplicationConfiguration(
            Config config) {
        Map<NodeId, TagProcessingPolicy> startupPolicies =
                new LinkedHashMap<NodeId, TagProcessingPolicy>();
        for (Config.TimingNodeConfig timingNode
                : config.timingNodes()) {
            startupPolicies.put(
                    timingNode.timingNodeId(),
                    timingNode.tagProcessingPolicy());
        }
        return new ApplicationConfiguration(
                startupPolicies);
    }

    /** Exposes only the mutable settings that presentation may update. */
    private static ConfigurationControl createConfigurationControl(
            ApplicationConfiguration configuration,
            DynamicConfiguration<Duration>
                    timingDataReplayPacketDelay) {
        Map<NodeId,
                DynamicConfiguration<TagProcessingPolicy>> tagProcessing =
                new LinkedHashMap<NodeId,
                        DynamicConfiguration<TagProcessingPolicy>>();

        for (NodeId nodeId
                : configuration.timingNodeIds()) {
            tagProcessing.put(
                    nodeId,
                    configuration
                            .timingNode(
                                    nodeId)
                            .tagProcessing());
        }

        return new ConfigurationControl(
                tagProcessing,
                timingDataReplayPacketDelay);
    }

    /** Validates the already resolved inputs required to build the runtime graph. */
    private static void requireCompositionInput(
            BuildIdentity buildIdentity,
            Config config,
            AntennaSet antennaSet,
            TimingSystemResolvedDataList resolvedSystems,
            PlatformEnvironment platform) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        for (Config.TimingNodeConfig timingNode
                : config.timingNodes()) {
            if (timingNode.timingDataPath() == null) {
                throw new IllegalArgumentException(
                        "TimingData storage path must be configured for TimingNode "
                                + timingNode
                                        .timingNodeId()
                                        .value());
            }
        }
        if (antennaSet == null) {
            throw new IllegalArgumentException(
                    "antennaSet must not be null");
        }
        if (resolvedSystems == null
                || resolvedSystems.isEmpty()) {
            throw new IllegalArgumentException(
                    "resolvedSystems must contain at least one TimingSystem");
        }
        if (!antennaSet.isEmpty()
                && resolvedSystems.size() != 1) {
            throw new IllegalArgumentException(
                    "Implicit antennas may only be composed for one TimingSystem");
        }
        if (resolvedSystems.size()
                != config.timingSystems().size()) {
            throw new IllegalArgumentException(
                    "Resolved TimingSystem count does not match configuration");
        }
        if (platform == null) {
            throw new IllegalArgumentException(
                    "platform must not be null");
        }
    }
}
