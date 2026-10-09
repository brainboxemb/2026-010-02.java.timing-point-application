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

        return TimingApplicationAssembly.assemble(
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

        return TimingApplicationAssembly.assemble(
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

        List<ResolvedTimingSystem> resolvedSystems =
                new ArrayList<ResolvedTimingSystem>(
                        config.timingSystems().size());

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
                    new ResolvedTimingSystem(
                            timingSystem,
                            SimulationEventDataProvider.ID.equals(
                                    eventDataProvider.id()),
                            eventDataProvider.createEventData(),
                            timingDataProvider.createFactory(),
                            timingDataProvider.createCodec()));
        }

        return TimingApplicationAssembly.assemble(
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
     * Composes the application after all implementation selections are resolved.
     */
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

    private static List<ResolvedTimingSystem> oneResolvedSystem(
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

        List<ResolvedTimingSystem> result =
                new ArrayList<ResolvedTimingSystem>();
        result.add(
                new ResolvedTimingSystem(
                        config.timingSystems().get(0),
                        tagScenarioSimulationEnabled,
                        eventData,
                        timingDataProvider.createFactory(),
                        timingDataProvider.createCodec()));
        return result;
    }

}
