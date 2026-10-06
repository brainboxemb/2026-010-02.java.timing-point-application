package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.EventDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.infra.extension.ExtensionRegistry;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.SimulatedPowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment.OperatingSystem;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
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
 * Conductor then owns lifecycle coordination of the composed application
 * components. Runtime owns shared workers and the outer Presentation lifecycle.</p>
 */
public final class TimingApplicationRuntime {
    private static final Logger LOG =
            LoggerFactory.getLogger(TimingApplicationRuntime.class);

    public enum State {
        NEW,
        ACTIVE,
        INACTIVE
    }

    private static final Duration ANTENNA_CONTROL_TIMEOUT =
            Duration.ofSeconds(2);
    private static final Duration SIMULATED_ANTENNA_POWER_STABILIZATION =
            Duration.ofMillis(200);

    private final BuildIdentity buildIdentity;
    private final TimingNode timingNode;
    private final ApplicationConfiguration configuration;
    private final PresentationGateway presentationGateway;
    private final RuntimeExecutors runtimeExecutors;
    private final AntennaManager antennaManager;
    private final Conductor conductor;
    private final PresentationRuntime presentationRuntime;
    private final ShutdownSignal shutdownSignal;

    private State state = State.NEW;

    private TimingApplicationRuntime(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ApplicationConfiguration configuration,
            PresentationGateway presentationGateway,
            RuntimeExecutors runtimeExecutors,
            AntennaManager antennaManager,
            Conductor conductor,
            PresentationRuntime presentationRuntime,
            ShutdownSignal shutdownSignal) {
        this.buildIdentity = buildIdentity;
        this.timingNode = timingNode;
        this.configuration = configuration;
        this.presentationGateway = presentationGateway;
        this.runtimeExecutors = runtimeExecutors;
        this.antennaManager = antennaManager;
        this.conductor = conductor;
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
        return createNormal(
                buildIdentity,
                config,
                PlatformEnvironment.system(),
                Thread.currentThread()
                        .getContextClassLoader(),
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
        return createNormal(
                buildIdentity,
                config,
                PlatformEnvironment.system(),
                extensionClassLoader,
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
        return createNormal(
                buildIdentity,
                config,
                platform,
                Thread.currentThread()
                        .getContextClassLoader(),
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
        return createNormal(
                buildIdentity,
                config,
                PlatformEnvironment.system(),
                Thread.currentThread()
                        .getContextClassLoader(),
                consoleInput,
                consoleOutput);
    }

    private static TimingApplicationRuntime createNormal(
            BuildIdentity buildIdentity,
            Config config,
            PlatformEnvironment platform,
            ClassLoader extensionClassLoader,
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

        return createConfigured(
                buildIdentity,
                config,
                platformDefaultAntennaInstallations(
                        platform),
                extensions,
                platform,
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
            List<AntennaInstallation> antennaInstallations,
            EventData eventData) {
        TimingDataProvider timingDataProvider =
                new DefaultTimingDataProvider();

        return createResolved(
                buildIdentity,
                config,
                antennaInstallations,
                eventData,
                timingDataProvider.createFactory(),
                timingDataProvider.createCodec(),
                PlatformEnvironment.system(),
                null,
                null);
    }

    /**
     * Resolves configured provider IDs before normal application composition.
     */
    private static TimingApplicationRuntime createConfigured(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            ExtensionRegistry extensions,
            PlatformEnvironment platform,
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

        EventDataProvider eventDataProvider =
                extensions.eventDataProvider(
                        config.eventDataProviderId());
        TimingDataProvider timingDataProvider =
                extensions.timingDataProvider(
                        config.timingDataProviderId());

        LOG.info(
                "Selected EventDataProvider id={} implementation={}",
                eventDataProvider.id(),
                eventDataProvider.getClass().getName());
        LOG.info(
                "Selected TimingDataProvider id={} implementation={}",
                timingDataProvider.id(),
                timingDataProvider.getClass().getName());

        return createResolved(
                buildIdentity,
                config,
                antennaInstallations,
                eventDataProvider.createEventData(),
                timingDataProvider.createFactory(),
                timingDataProvider.createCodec(),
                platform,
                consoleInput,
                consoleOutput);
    }

    /**
     * Composes the application after all implementation selections are resolved.
     */
    private static TimingApplicationRuntime createResolved(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            EventData eventData,
            TimingDataFactory timingDataFactory,
            TimingDataCodec timingDataCodec,
            PlatformEnvironment platform,
            Reader consoleInput,
            Writer consoleOutput) {
        requireCompositionInput(
                buildIdentity,
                config,
                antennaInstallations,
                eventData,
                timingDataFactory,
                timingDataCodec,
                platform);

        List<AntennaInstallation> installations =
                copyInstallations(
                        antennaInstallations);

        ApplicationConfiguration applicationConfiguration =
                ApplicationConfiguration.singleTimingNode(
                        config.timingNodeId(),
                        config.tagProcessingPolicy());

        RuntimeExecutors executors =
                new RuntimeExecutors();

        try {
            RuntimeExecutors.TimingNodeExecutors nodeExecutors =
                    executors.createTimingNodeExecutors();

            TimingDataPersistence persistence =
                    new DefaultTimingDataPersistence(
                            new FileAppendOnlyRecordStore(
                                    config.timingDataPath()),
                            config.timingNodeId(),
                            timingDataCodec);

            TimingNode timingNode =
                    new TimingNode(
                            config.timingNodeId(),
                            persistence,
                            timingDataFactory,
                            () -> new TimingTimestamp(
                                    platform.clock().instant()),
                            applicationConfiguration
                                    .timingNode(
                                            config.timingNodeId())
                                    .tagProcessing(),
                            eventData,
                            nodeExecutors.timingNode(),
                            nodeExecutors.tagProcessor(),
                            platform.monotonicClock());

            AntennaManager antennaManager = null;
            if (!installations.isEmpty()) {
                antennaManager =
                        new AntennaManager(
                                installations,
                                executors
                                        .createAntennaControlExecutor(),
                                ANTENNA_CONTROL_TIMEOUT);
            }

            Conductor conductor =
                    new Conductor(
                            timingNode,
                            antennaManager,
                            executors
                                    .createConductorExecutor());

            ConfigurationControl configurationControl =
                    createConfigurationControl(
                            applicationConfiguration);

            PresentationGateway presentationGateway =
                    new PresentationGateway(
                            buildIdentity,
                            timingNode,
                            configurationControl);

            ShutdownSignal shutdownSignal =
                    new ShutdownSignal();

            PresentationRuntime presentation =
                    new PresentationRuntime(
                            config.presentation(),
                            presentationGateway,
                            shutdownSignal::request,
                            consoleInput,
                            consoleOutput);

            /*
             * Cross-component relationships stay visible at composition.
             */
            timingNode.statusChangedEvent()
                    .subscribe(
                            ignored ->
                                    conductor
                                            .timingNodeStateProperty()
                                            .signalChanged());

            if (antennaManager != null) {
                for (AntennaInstallation installation
                        : installations) {
                    antennaManager
                            .tagObservedEvent(
                                    installation.antennaId())
                            .subscribe(
                                    timingNode
                                            .tagProcessor()
                                            ::onTagObserved);
                }
            }

            return new TimingApplicationRuntime(
                    buildIdentity,
                    timingNode,
                    applicationConfiguration,
                    presentationGateway,
                    executors,
                    antennaManager,
                    conductor,
                    presentation,
                    shutdownSignal);
        } catch (RuntimeException | Error failure) {
            executors.close();
            throw failure;
        }
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
            conductor.activate();
            presentationRuntime.activate();
            state = State.ACTIVE;
        } catch (RuntimeException | Error failure) {
            try {
                presentationRuntime.deactivate();
            } catch (RuntimeException | Error deactivateFailure) {
                failure.addSuppressed(
                        deactivateFailure);
            }
            try {
                conductor.deactivate();
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
        return timingNode;
    }

    AntennaManager antennaManager() {
        return antennaManager;
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
     * Stops outer Presentation first, then Conductor/application components,
     * then Runtime-owned physical workers.
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

        try {
            conductor.deactivate();
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

    /**
     * Temporary Windows development fallback until explicit IF-11 antenna
     * configuration is composed by the normal runtime mapper.
     */
    private static List<AntennaInstallation> platformDefaultAntennaInstallations(
            PlatformEnvironment platform) {
        if (platform.operatingSystem()
                != OperatingSystem.WINDOWS) {
            return Collections.emptyList();
        }

        LOG.warn(
                "Windows development platform default selected simulated antenna ANT1; no physical RFID reader is in use");

        SimulatedAntenna antenna =
                new SimulatedAntenna();

        return Collections.singletonList(
                AntennaInstallation.powered(
                        new AntennaId("ANT1"),
                        antenna,
                        new SimulatedPowerDevice(
                                antenna),
                        SIMULATED_ANTENNA_POWER_STABILIZATION));
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

    private static List<AntennaInstallation> copyInstallations(
            List<AntennaInstallation> installations) {
        List<AntennaInstallation> copy =
                new ArrayList<AntennaInstallation>(
                        installations.size());

        for (AntennaInstallation installation
                : installations) {
            if (installation == null) {
                throw new IllegalArgumentException(
                        "antennaInstallations must not contain null");
            }
            copy.add(installation);
        }

        return Collections.unmodifiableList(
                copy);
    }

    private static void requireCompositionInput(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            EventData eventData,
            TimingDataFactory timingDataFactory,
            TimingDataCodec timingDataCodec,
            PlatformEnvironment platform) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        if (config.timingDataPath() == null) {
            throw new IllegalArgumentException(
                    "TimingData storage path must be configured before composition");
        }
        if (antennaInstallations == null) {
            throw new IllegalArgumentException(
                    "antennaInstallations must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (timingDataFactory == null) {
            throw new IllegalArgumentException(
                    "timingDataFactory must not be null");
        }
        if (timingDataCodec == null) {
            throw new IllegalArgumentException(
                    "timingDataCodec must not be null");
        }
        if (platform == null) {
            throw new IllegalArgumentException(
                    "platform must not be null");
        }
    }
}
