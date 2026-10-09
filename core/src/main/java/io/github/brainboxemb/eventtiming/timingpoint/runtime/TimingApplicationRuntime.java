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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
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
    private final List<TimingNode> timingNodes;
    private final ApplicationConfiguration configuration;
    private final PresentationGateway presentationGateway;
    private final RuntimeExecutors runtimeExecutors;
    private final List<AntennaManager> antennaManagers;
    private final SimulatedTagScenarioRunner simulationRunner;
    private final ApplicationConductor applicationConductor;
    private final PresentationRuntime presentationRuntime;
    private final ShutdownSignal shutdownSignal;

    private State state = State.NEW;

    private TimingApplicationRuntime(
            BuildIdentity buildIdentity,
            List<TimingNode> timingNodes,
            ApplicationConfiguration configuration,
            PresentationGateway presentationGateway,
            RuntimeExecutors runtimeExecutors,
            List<AntennaManager> antennaManagers,
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
        this.antennaManagers =
                Collections.unmodifiableList(
                        new ArrayList<AntennaManager>(
                                antennaManagers));
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
        return createNormal(
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
        return createNormal(
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
        return createNormal(
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
        return createNormal(
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
        return createNormal(
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


    private static TimingApplicationRuntime createNormal(
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
                platformDefaultAntennaComposition(
                        platform,
                        config.timingSystems().size(),
                        config.timingNodes().size());

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

        return createResolved(
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

        return createResolved(
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

        return createResolved(
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
    private static TimingApplicationRuntime createResolved(
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
            List<AntennaManager> antennaManagers =
                    new ArrayList<AntennaManager>();
            ApplicationConductor applicationConductor =
                    new ApplicationConductor();
            SimulatedTagScenarioRunner simulationRunner = null;

            for (ResolvedTimingSystem resolvedSystem
                    : resolvedSystems) {
                TimeSource timeSource =
                        runtimeTimeSources.createTimeSource();
                List<TimingNode> systemNodes =
                        new ArrayList<TimingNode>(
                                resolvedSystem
                                        .configuration
                                        .timingNodes()
                                        .size());

                for (Config.TimingNodeConfig nodeConfig
                        : resolvedSystem
                                .configuration
                                .timingNodes()) {
                    RuntimeExecutors.TimingNodeExecutors nodeExecutors =
                            executors.createTimingNodeExecutors();

                    TimingDataPersistence persistence =
                            new DefaultTimingDataPersistence(
                                    new FileAppendOnlyRecordStore(
                                            nodeConfig.timingDataPath()),
                                    nodeConfig.timingNodeId(),
                                    resolvedSystem.timingDataCodec);

                    TimingNode timingNode =
                            new TimingNode(
                                    nodeConfig.timingNodeId(),
                                    persistence,
                                    resolvedSystem.timingDataFactory,
                                    timeSource,
                                    applicationConfiguration
                                            .timingNode(
                                                    nodeConfig
                                                            .timingNodeId())
                                            .tagProcessing(),
                                    resolvedSystem.eventData,
                                    nodeExecutors.timingNode(),
                                    nodeExecutors.tagProcessor(),
                                    platform.monotonicClock());
                    systemNodes.add(
                            timingNode);
                    timingNodes.add(
                            timingNode);
                }

                AntennaManager antennaManager = null;
                if (!antennaSet.isEmpty()) {
                    if (resolvedSystems.size() != 1
                            || systemNodes.size() != 1) {
                        throw new IllegalArgumentException(
                                "Implicit antenna composition requires exactly one TimingSystem with one TimingNode");
                    }
                    antennaManager =
                            new AntennaManager(
                                    antennaSet,
                                    executors
                                            .createAntennaControlExecutor(),
                                    ANTENNA_CONTROL_TIMEOUT);
                    antennaManagers.add(
                            antennaManager);
                }

                Conductor systemConductor =
                        new Conductor(
                                systemNodes,
                                antennaManager,
                                executors
                                        .createSystemConductorExecutor());

                applicationConductor.registerTimingSystem(
                        antennaManager,
                        systemConductor);

                for (TimingNode timingNode : systemNodes) {
                    timingNode.statusChangedEvent()
                            .subscribe(
                                    ignored ->
                                            systemConductor
                                                    .signalTimingNodeStateChanged(
                                                            timingNode));
                }

                if (antennaManager != null) {
                    TimingNode timingNode =
                            systemNodes.get(0);
                    for (AntennaId antennaId
                            : antennaSet.antennaIds()) {
                        antennaManager.tagObservedEvent(
                                        antennaId)
                                .subscribe(
                                        timingNode
                                                .tagProcessor()
                                                ::onTagObserved);
                    }
                }

                if (resolvedSystems.size() == 1
                        && systemNodes.size() == 1
                        && resolvedSystem.tagScenarioSimulationEnabled
                        && simulatedAntenna != null
                        && !resolvedSystem.eventData.isEmpty()) {
                    simulationRunner =
                            new SimulatedTagScenarioRunner(
                                    simulatedAntenna,
                                    resolvedSystem.eventData,
                                    timeSource,
                                    executors
                                            .createSimulationExecutor());
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
                    antennaManagers,
                    simulationRunner,
                    applicationConductor,
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
        if (antennaManagers.isEmpty()) {
            return null;
        }
        if (antennaManagers.size() != 1) {
            throw new IllegalStateException(
                    "Operation requires at most one AntennaManager; composed="
                            + antennaManagers.size());
        }
        return antennaManagers.get(0);
    }

    List<AntennaManager> antennaManagers() {
        return antennaManagers;
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

    private static final class ResolvedTimingSystem {
        private final Config.TimingSystemConfig configuration;
        private final boolean tagScenarioSimulationEnabled;
        private final EventData eventData;
        private final TimingDataFactory timingDataFactory;
        private final TimingDataCodec timingDataCodec;

        private ResolvedTimingSystem(
                Config.TimingSystemConfig configuration,
                boolean tagScenarioSimulationEnabled,
                EventData eventData,
                TimingDataFactory timingDataFactory,
                TimingDataCodec timingDataCodec) {
            if (configuration == null) {
                throw new IllegalArgumentException(
                        "configuration must not be null");
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

            this.configuration = configuration;
            this.tagScenarioSimulationEnabled =
                    tagScenarioSimulationEnabled;
            this.eventData = eventData;
            this.timingDataFactory = timingDataFactory;
            this.timingDataCodec = timingDataCodec;
        }
    }
}
