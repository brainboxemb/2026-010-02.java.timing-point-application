package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.simulation.SimulationEventDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistry;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment.OperatingSystem;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.simulation.SimulatedTagScenarioRunner;

import java.io.Reader;
import java.io.Writer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolves normal, simulation and Windows development startup inputs. */
final class TimingApplicationComposition {
    private static final Logger LOG =
            LoggerFactory.getLogger(TimingApplicationComposition.class);
    private static final Duration SIMULATED_ANTENNA_POWER_STABILIZATION =
            Duration.ofMillis(200);

    private TimingApplicationComposition() {
    }

    static TimingApplicationRuntime createNormal(
            BuildIdentity buildIdentity,
            Config config,
            PlatformEnvironment platform,
            ClassLoader extensionClassLoader,
            LoggingLevelControl loggingLevelControl,
            ConsolePromptControl consolePromptControl,
            Reader consoleInput,
            Writer consoleOutput) {
        if (platform == null) {
            throw new IllegalArgumentException(
                    "platform must not be null");
        }
        if (extensionClassLoader == null) {
            throw new IllegalArgumentException(
                    "extensionClassLoader must not be null");
        }

        ExtensionRegistry extensions =
                ExtensionRegistry.discover(
                        extensionClassLoader);

        AntennaComposition antennas =
                config.antennaManagers().isEmpty()
                        ? platformDefaultAntennaComposition(
                                platform,
                                config.timingSystems().size(),
                                config.timingNodes().size())
                        : new AntennaComposition(new AntennaSet(), null);

        return createConfigured(
                buildIdentity,
                config,
                antennas.antennaSet,
                antennas.simulatedAntenna,
                extensions,
                platform,
                loggingLevelControl,
                consolePromptControl,
                consoleInput,
                consoleOutput);
    }

    /**
     * Package-local simulation seam that keeps explicit EventData injection out
     * of the public Runtime API while reusing the same resolved composition.
     */
    static TimingApplicationRuntime createSimulation(
            BuildIdentity buildIdentity,
            Config config,
            AntennaSet antennaSet,
            EventData eventData) {
        TimingDataProvider timingDataProvider =
                new DefaultTimingDataProvider();

        return assemble(
                buildIdentity,
                config,
                antennaSet,
                null,
                oneResolvedSystem(
                        config,
                        false,
                        eventData,
                        timingDataProvider),
                PlatformEnvironment.system(),
                null,
                null,
                null,
                null);
    }

    /**
     * Package-local simulator composition with one explicitly controllable
     * SimulatedAntenna for the IF-03 tag-scenario capability.
     */
    static TimingApplicationRuntime createSimulation(
            BuildIdentity buildIdentity,
            Config config,
            SimulatedAntenna antenna,
            EventData eventData) {
        if (antenna == null) {
            throw new IllegalArgumentException(
                    "antenna must not be null");
        }

        AntennaSet antennaSet =
                new AntennaSet()
                        .add(
                                new AntennaId("1"),
                                antenna);
        TimingDataProvider timingDataProvider =
                new DefaultTimingDataProvider();

        return assemble(
                buildIdentity,
                config,
                antennaSet,
                antenna,
                oneResolvedSystem(
                        config,
                        true,
                        eventData,
                        timingDataProvider),
                PlatformEnvironment.system(),
                null,
                null,
                null,
                null);
    }

    /**
     * Resolves configured provider IDs before normal application composition.
     */
    private static TimingApplicationRuntime createConfigured(
            BuildIdentity buildIdentity,
            Config config,
            AntennaSet antennaSet,
            SimulatedAntenna simulatedAntenna,
            ExtensionRegistry extensions,
            PlatformEnvironment platform,
            LoggingLevelControl loggingLevelControl,
            ConsolePromptControl consolePromptControl,
            Reader consoleInput,
            Writer consoleOutput) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        if (extensions == null) {
            throw new IllegalArgumentException(
                    "extensions must not be null");
        }

        TimingSystemResolvedDataList resolvedSystems =
                new TimingSystemResolvedDataList();

        for (Config.TimingSystemConfig timingSystem
                : config.timingSystems()) {
            EventDataProvider eventDataProvider =
                    extensions.eventDataProvider(
                            timingSystem
                                    .eventDataProviderId());
            TimingDataProvider timingDataProvider =
                    extensions.timingDataProvider(
                            timingSystem
                                    .timingDataProviderId());

            LOG.info(
                    "TimingSystem {} selected EventDataProvider id={} implementation={}",
                    timingSystem.timingSystemId(),
                    eventDataProvider.id(),
                    eventDataProvider.getClass().getName());
            LOG.info(
                    "TimingSystem {} selected TimingDataProvider id={} implementation={}",
                    timingSystem.timingSystemId(),
                    timingDataProvider.id(),
                    timingDataProvider.getClass().getName());

            resolvedSystems.add(
                    new TimingSystemResolvedData(
                            timingSystem,
                            SimulationEventDataProvider.ID.equals(
                                    eventDataProvider.id()),
                            eventDataProvider.createEventData(),
                            timingDataProvider.createFactory(),
                            timingDataProvider.createCodec()));
        }

        return assemble(
                buildIdentity,
                config,
                antennaSet,
                simulatedAntenna,
                resolvedSystems,
                platform,
                loggingLevelControl,
                consolePromptControl,
                consoleInput,
                consoleOutput);
    }

    /**
     * Temporary Windows development fallback until explicit IF-11 antenna
     * configuration is composed by the normal runtime mapper.
     */
    private static AntennaComposition platformDefaultAntennaComposition(
            PlatformEnvironment platform,
            int timingSystemCount,
            int timingNodeCount) {
        AntennaSet antennas =
                new AntennaSet();

        if (platform.operatingSystem()
                        != OperatingSystem.WINDOWS
                || timingSystemCount != 1
                || timingNodeCount != 1) {
            return new AntennaComposition(
                    antennas,
                    null);
        }

        LOG.warn(
                "Windows development platform default selected simulated antenna 1; no physical RFID reader is in use");

        SimulatedAntenna antenna =
                new SimulatedAntenna();
        antennas.addPowered(
                new AntennaId("1"),
                antenna,
                new SimulatedPowerDevice(antenna),
                SIMULATED_ANTENNA_POWER_STABILIZATION);
        return new AntennaComposition(
                antennas,
                antenna);
    }

    private static final class AntennaComposition {
        private final AntennaSet antennaSet;
        private final SimulatedAntenna simulatedAntenna;

        private AntennaComposition(
                AntennaSet antennaSet,
                SimulatedAntenna simulatedAntenna) {
            this.antennaSet = antennaSet;
            this.simulatedAntenna = simulatedAntenna;
        }
    }

    private static TimingSystemResolvedDataList oneResolvedSystem(
            Config config,
            boolean tagScenarioSimulationEnabled,
            EventData eventData,
            TimingDataProvider timingDataProvider) {
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        if (config.timingSystems().size() != 1) {
            throw new IllegalArgumentException(
                    "Simulation composition requires exactly one TimingSystem");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (timingDataProvider == null) {
            throw new IllegalArgumentException(
                    "timingDataProvider must not be null");
        }

        TimingSystemResolvedDataList result =
                new TimingSystemResolvedDataList();
        result.add(
                new TimingSystemResolvedData(
                        config.timingSystems().get(0),
                        tagScenarioSimulationEnabled,
                        eventData,
                        timingDataProvider.createFactory(),
                        timingDataProvider.createCodec()));
        return result;
    }


    /** Builds the complete application after resolving its configured providers. */
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
            TimingNodeList timingNodes =
                    new TimingNodeList();
            TimingSystemComponentsList systems =
                    new TimingSystemComponentsList();
            ApplicationConductor applicationConductor =
                    new ApplicationConductor();
            SimulatedTagScenarioRunner simulationRunner = null;

            TimingSystemComposer systemComposer = new TimingSystemComposer(
                    applicationConfiguration, executors, platform, applicationConductor);

            for (TimingSystemResolvedData resolvedSystem : resolvedSystems) {
                TimeSource timeSource = runtimeTimeSources.createTimeSource();
                TimingSystemComponents system = systemComposer.compose(
                        resolvedSystem,
                        config.antennaManager(resolvedSystem.configuration().timingSystemId()),
                        antennaSet,
                        timeSource);
                systems.add(system);
                for (TimingNode node : system.nodes()) {
                    timingNodes.add(node);
                }

                if (resolvedSystems.size() == 1
                        && system.nodes().size() == 1
                        && resolvedSystem.tagScenarioSimulationEnabled()
                        && simulatedAntenna != null
                        && !resolvedSystem.eventData().isEmpty()) {
                    simulationRunner = new SimulatedTagScenarioRunner(
                            simulatedAntenna,
                            resolvedSystem.eventData(),
                            timeSource,
                            executors.createSimulationExecutor());
                }
            }

            ConfigurationControl configurationControl =
                    createConfigurationControl(
                            applicationConfiguration);

            PresentationGateway presentationGateway =
                    new PresentationGateway(
                            buildIdentity,
                            timingNodes,
                            configurationControl,
                            simulationRunner);

            ShutdownSignal shutdownSignal =
                    new ShutdownSignal();

            PresentationRuntime presentation =
                    new PresentationRuntime(
                            config.presentation(),
                            presentationGateway,
                            loggingLevelControl,
                            consolePromptControl,
                            shutdownSignal::request,
                            consoleInput,
                            consoleOutput);

            return new TimingApplicationRuntime(
                    buildIdentity,
                    timingNodes,
                    applicationConfiguration,
                    presentationGateway,
                    executors,
                    systems,
                    simulationRunner,
                    applicationConductor,
                    presentation,
                    shutdownSignal);
        } catch (RuntimeException | Error failure) {
            executors.close();
            throw failure;
        }
    }

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

    private static ConfigurationControl createConfigurationControl(
            ApplicationConfiguration configuration) {
        Map<NodeId,
                DynamicConfiguration<TagProcessingPolicy>> tagProcessing =
                new LinkedHashMap<NodeId,
                        DynamicConfiguration<TagProcessingPolicy>>();

        for (NodeId nodeId
                : configuration.timingNodeIds()) {
            tagProcessing.put(
                    nodeId,
                    configuration
                            .timingNode(nodeId)
                            .tagProcessing());
        }

        return new ConfigurationControl(
                tagProcessing);
    }

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
                                + timingNode.timingNodeId().value());
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
        if (!antennaSet.isEmpty() && resolvedSystems.size() != 1) {
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
