package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;
import io.github.brainboxemb.eventtiming.eventdata.simulation.SimulationEventDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistry;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment.OperatingSystem;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfig;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.io.Reader;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds the configured application and connects its system components. */
final class TimingApplicationAssembly {
    private TimingApplicationAssembly() {
    }

    static TimingApplicationRuntime assemble(
            BuildIdentity buildIdentity,
            Config config,
            AntennaSet antennaSet,
            SimulatedAntenna simulatedAntenna,
            List<ResolvedTimingSystem> resolvedSystems,
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
            List<TimingNode> timingNodes =
                    new ArrayList<TimingNode>(
                            config.timingNodes().size());
            List<TimingSystemComponents> systems =
                    new ArrayList<TimingSystemComponents>();
            ApplicationConductor applicationConductor =
                    new ApplicationConductor();
            SimulatedTagScenarioRunner simulationRunner = null;

            TimingSystemComposer systemComposer = new TimingSystemComposer(
                    applicationConfiguration, executors, platform, applicationConductor);

            for (ResolvedTimingSystem resolvedSystem : resolvedSystems) {
                TimeSource timeSource = runtimeTimeSources.createTimeSource();
                TimingSystemComponents system = systemComposer.compose(
                        resolvedSystem,
                        config.antennaManager(resolvedSystem.configuration.timingSystemId()),
                        antennaSet,
                        timeSource);
                systems.add(system);
                timingNodes.addAll(system.nodes());

                if (resolvedSystems.size() == 1
                        && system.nodes().size() == 1
                        && resolvedSystem.tagScenarioSimulationEnabled
                        && simulatedAntenna != null
                        && !resolvedSystem.eventData.isEmpty()) {
                    simulationRunner = new SimulatedTagScenarioRunner(
                            simulatedAntenna,
                            resolvedSystem.eventData,
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
            List<ResolvedTimingSystem> resolvedSystems,
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
