package oss.relay.eventtiming.timingpoint.runtime;

import oss.relay.eventtiming.eventdata.EventData;
import oss.relay.eventtiming.timingdata.TimingDataProvider;
import oss.relay.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;
import oss.relay.eventtiming.timingpoint.infra.BuildIdentity;
import oss.relay.eventtiming.timingpoint.infra.extension.ExtensionRegistry;
import oss.relay.eventtiming.timingpoint.infra.logging.ConsolePromptControl;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import oss.relay.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;
import oss.relay.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import oss.relay.eventtiming.timingpoint.platform.environment.PlatformEnvironment.OperatingSystem;
import oss.relay.eventtiming.timingpoint.runtime.config.Config;

import java.io.Reader;
import java.io.Writer;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Selects normal, simulation and Windows-development startup inputs. */
final class TimingApplicationComposition {
    private static final Logger LOG =
            LoggerFactory.getLogger(
                    TimingApplicationComposition.class);
    private static final Duration SIMULATED_ANTENNA_POWER_STABILIZATION =
            Duration.ofMillis(
                    200);

    private TimingApplicationComposition() {
    }

    /** Composes the normal runtime from discovered providers and platform defaults. */
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
                        : new AntennaComposition(
                                new AntennaSet(),
                                null);

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

        return TimingApplicationAssembler.assemble(
                buildIdentity,
                config,
                antennaSet,
                null,
                TimingSystemDataResolver.resolveSimulation(
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

        return TimingApplicationAssembler.assemble(
                buildIdentity,
                config,
                antennaSet,
                antenna,
                TimingSystemDataResolver.resolveSimulation(
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

    /** Resolves configured providers before handing the object graph to the assembler. */
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
        return TimingApplicationAssembler.assemble(
                buildIdentity,
                config,
                antennaSet,
                simulatedAntenna,
                TimingSystemDataResolver.resolveConfigured(
                        config,
                        extensions),
                platform,
                loggingLevelControl,
                consolePromptControl,
                consoleInput,
                consoleOutput);
    }

    /**
     * Selects the temporary single-node Windows development antenna fallback.
     *
     * <p>Configured IF-11 antenna managers bypass this fallback entirely.</p>
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
                new SimulatedPowerDevice(
                        antenna),
                SIMULATED_ANTENNA_POWER_STABILIZATION);
        return new AntennaComposition(
                antennas,
                antenna);
    }

    /** Startup-only result of selecting implicit platform antenna input. */
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
}
