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

/**
 * One completely composed SI-01 timing application.
 *
 * <p>This is the visible composition root and owner of process-level Runtime
 * resources. Provider discovery is resolved before normal object composition;
 * the Application Conductor owns application lifecycle and each system Conductor owns its TimingNodes.
 * Runtime owns shared workers and the outer Presentation lifecycle.</p>
 */
public final class TimingApplicationRuntime {
    public enum State {
        NEW,
        ACTIVE,
        INACTIVE
    }

    private final BuildIdentity buildIdentity;
    private final List<TimingNode> timingNodes;
    private final ApplicationConfiguration configuration;
    private final PresentationGateway presentationGateway;
    private final RuntimeExecutors runtimeExecutors;
    private final List<TimingSystemComponents> systems;
    private final SimulatedTagScenarioRunner simulationRunner;
    private final ApplicationConductor applicationConductor;
    private final PresentationRuntime presentationRuntime;
    private final ShutdownSignal shutdownSignal;

    private State state = State.NEW;

    TimingApplicationRuntime(
            BuildIdentity buildIdentity,
            List<TimingNode> timingNodes,
            ApplicationConfiguration configuration,
            PresentationGateway presentationGateway,
            RuntimeExecutors runtimeExecutors,
            List<TimingSystemComponents> systems,
            SimulatedTagScenarioRunner simulationRunner,
            ApplicationConductor applicationConductor,
            PresentationRuntime presentationRuntime,
            ShutdownSignal shutdownSignal) {
        this.buildIdentity = buildIdentity;
        this.timingNodes =
                Collections.unmodifiableList(
                        new ArrayList<TimingNode>(
                                timingNodes));
        this.configuration = configuration;
        this.presentationGateway = presentationGateway;
        this.runtimeExecutors = runtimeExecutors;
        this.systems = Collections.unmodifiableList(
                new ArrayList<TimingSystemComponents>(systems));
        this.simulationRunner = simulationRunner;
        this.applicationConductor = applicationConductor;
        this.presentationRuntime = presentationRuntime;
        this.shutdownSignal = shutdownSignal;
    }

    /**
     * Constructs the normal application using the current platform and context
     * ClassLoader for typed extension discovery.
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config) {
        return TimingApplicationComposition.createNormal(
                buildIdentity,
                config,
                PlatformEnvironment.system(),
                Thread.currentThread()
                        .getContextClassLoader(),
                null,
                null,
                null,
                null);
    }

    /**
     * Constructs the application using one explicit extension ClassLoader.
     *
     * <p>The caller owns the ClassLoader lifecycle. This seam allows a dedicated
     * URLClassLoader over external provider JARs without making Runtime own a
     * filesystem extension directory.</p>
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            ClassLoader extensionClassLoader) {
        return TimingApplicationComposition.createNormal(
                buildIdentity,
                config,
                PlatformEnvironment.system(),
                extensionClassLoader,
                null,
                null,
                null,
                null);
    }

    /**
     * Package-local seam for deterministic platform-dependent composition tests.
     */
    static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            PlatformEnvironment platform) {
        return TimingApplicationComposition.createNormal(
                buildIdentity,
                config,
                platform,
                Thread.currentThread()
                        .getContextClassLoader(),
                null,
                null,
                null,
                null);
    }

    /**
     * Constructs the normal executable composition with process console I/O.
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            Reader consoleInput,
            Writer consoleOutput) {
        return TimingApplicationComposition.createNormal(
                buildIdentity,
                config,
                PlatformEnvironment.system(),
                Thread.currentThread()
                        .getContextClassLoader(),
                null,
                null,
                consoleInput,
                consoleOutput);
    }

    /**
     * Constructs the normal executable composition with process console I/O and
     * runtime log-level control for local/remote terminal commands.
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            LoggingLevelControl loggingLevelControl,
            Reader consoleInput,
            Writer consoleOutput) {
        return create(
                buildIdentity,
                config,
                loggingLevelControl,
                null,
                consoleInput,
                consoleOutput);
    }

    /**
     * Constructs the normal executable composition with explicit local-console
     * prompt coordination for asynchronous console logging.
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            LoggingLevelControl loggingLevelControl,
            ConsolePromptControl consolePromptControl,
            Reader consoleInput,
            Writer consoleOutput) {
        return TimingApplicationComposition.createNormal(
                buildIdentity,
                config,
                PlatformEnvironment.system(),
                Thread.currentThread()
                        .getContextClassLoader(),
                loggingLevelControl,
                consolePromptControl,
                consoleInput,
                consoleOutput);
    }


    /** Test seam for injected antenna devices and EventData. */
    static TimingApplicationRuntime createSimulation(
            BuildIdentity identity,
            Config config,
            AntennaSet antennas,
            EventData eventData) {
        return TimingApplicationComposition.createSimulation(
                identity, config, antennas, eventData);
    }

    /** Test seam for the controllable simulated antenna. */
    static TimingApplicationRuntime createSimulation(
            BuildIdentity identity,
            Config config,
            SimulatedAntenna antenna,
            EventData eventData) {
        return TimingApplicationComposition.createSimulation(
                identity, config, antenna, eventData);
    }

    /**
     * Activates the already constructed and wired application.
     */
    public synchronized void activate() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "TimingApplicationRuntime can only activate from NEW; current state="
                            + state);
        }

        try {
            runtimeExecutors.start();
            applicationConductor.activate();
            if (simulationRunner != null) {
                simulationRunner.activate();
            }
            presentationRuntime.activate();
            state = State.ACTIVE;
        } catch (RuntimeException | Error failure) {
            try {
                presentationRuntime.deactivate();
            } catch (RuntimeException | Error deactivateFailure) {
                failure.addSuppressed(
                        deactivateFailure);
            }
            if (simulationRunner != null) {
                try {
                    simulationRunner.deactivate();
                } catch (RuntimeException | Error deactivateFailure) {
                    failure.addSuppressed(
                            deactivateFailure);
                }
            }
            try {
                applicationConductor.deactivate();
            } catch (RuntimeException | Error deactivateFailure) {
                failure.addSuppressed(
                        deactivateFailure);
            }
            try {
                runtimeExecutors.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(
                        closeFailure);
            }
            state = State.INACTIVE;
            notifyAll();
            throw failure;
        }
    }

    public PresentationGateway presentationGateway() {
        return presentationGateway;
    }

    ApplicationConfiguration configuration() {
        return configuration;
    }

    TimingNode timingNode() {
        if (timingNodes.size() != 1) {
            throw new IllegalStateException(
                    "Operation requires exactly one TimingNode; composed="
                            + timingNodes.size());
        }
        return timingNodes.get(0);
    }

    List<TimingNode> timingNodes() {
        return timingNodes;
    }

    AntennaManager antennaManager() {
        List<AntennaManager> managers = antennaManagers();
        if (managers.isEmpty()) {
            return null;
        }
        if (managers.size() != 1) {
            throw new IllegalStateException(
                    "Operation requires at most one AntennaManager; composed=" + managers.size());
        }
        return managers.get(0);
    }

    List<AntennaManager> antennaManagers() {
        List<AntennaManager> managers = new ArrayList<AntennaManager>();
        for (TimingSystemComponents system : systems) {
            if (system.antennaManager() != null) {
                managers.add(system.antennaManager());
            }
        }
        return Collections.unmodifiableList(managers);
    }

    public synchronized State state() {
        return state;
    }

    public void awaitShutdownRequest()
            throws InterruptedException {
        shutdownSignal.awaitRequest();
    }

    public String smokeOutput() {
        return smokeOutput(
                buildIdentity,
                state());
    }

    /**
     * Stops outer Presentation first, then the Application Conductor (which
     * stops system components in reverse order), then Runtime-owned workers.
     */
    public synchronized void deactivate() {
        shutdownSignal.request();

        if (state == State.INACTIVE) {
            return;
        }

        Throwable firstFailure = null;

        try {
            presentationRuntime.deactivate();
        } catch (RuntimeException | Error failure) {
            firstFailure = failure;
        }

        if (simulationRunner != null) {
            try {
                simulationRunner.deactivate();
            } catch (RuntimeException | Error failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                } else {
                    firstFailure.addSuppressed(
                            failure);
                }
            }
        }

        try {
            applicationConductor.deactivate();
        } catch (RuntimeException | Error failure) {
            if (firstFailure == null) {
                firstFailure = failure;
            } else {
                firstFailure.addSuppressed(
                        failure);
            }
        }

        try {
            runtimeExecutors.close();
        } catch (RuntimeException | Error failure) {
            if (firstFailure == null) {
                firstFailure = failure;
            } else {
                firstFailure.addSuppressed(
                        failure);
            }
        }

        state = State.INACTIVE;
        notifyAll();

        rethrow(firstFailure);
    }

    private static void rethrow(
            Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw (Error) failure;
    }

    public static String smokeOutput(
            BuildIdentity buildIdentity,
            State state) {
        return buildIdentity.application()
                + " lifecycle OK version="
                + buildIdentity.version()
                + " state="
                + state;
    }

}
