package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventDataProvider;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;

import java.io.Reader;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One completely composed SI-01 timing application.
 *
 * <p>This is the visible composition root and owner of process-level Runtime
 * resources. {@link #create(BuildIdentity, Config)} constructs and wires the
 * graph without starting physical application workers. Conductor owns lifecycle
 * coordination of the application components; Runtime owns shared workers and
 * the outer Presentation lifecycle.</p>
 *
 * <p>The production path is deliberately readable in one place:</p>
 *
 * <pre>
 * configuration
 *   -> runtime resources
 *   -> Domain / I/O / Application objects
 *   -> explicit event wiring
 *   -> TimingApplicationRuntime
 * </pre>
 */
public final class TimingApplicationRuntime {

    public enum State {
        NEW,
        ACTIVE,
        INACTIVE
    }

    private static final Duration ANTENNA_CONTROL_TIMEOUT =
            Duration.ofSeconds(2);

    private final BuildIdentity buildIdentity;
    private final TimingNode timingNode;
    private final ApplicationConfiguration configuration;
    private final PresentationGateway presentationGateway;
    private final RuntimeExecutors runtimeExecutors;
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
            Conductor conductor,
            PresentationRuntime presentationRuntime,
            ShutdownSignal shutdownSignal) {
        this.buildIdentity = buildIdentity;
        this.timingNode = timingNode;
        this.configuration = configuration;
        this.presentationGateway = presentationGateway;
        this.runtimeExecutors = runtimeExecutors;
        this.conductor = conductor;
        this.presentationRuntime = presentationRuntime;
        this.shutdownSignal = shutdownSignal;
    }

    /**
     * Constructs and wires the normal configured application.
     *
     * <p>No physical application worker is started by this method.</p>
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config) {
        return create(
                buildIdentity,
                config,
                Collections.<AntennaInstallation>emptyList(),
                defaultEventData(),
                null,
                null);
    }

    /**
     * Constructs the normal executable composition with process console I/O.
     *
     * <p>Main supplies process streams only; concrete Presentation adapters stay
     * owned by Runtime composition.</p>
     */
    public static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            Reader consoleInput,
            Writer consoleOutput) {
        return create(
                buildIdentity,
                config,
                Collections.<AntennaInstallation>emptyList(),
                defaultEventData(),
                consoleInput,
                consoleOutput);
    }

    /**
     * Package-local simulation seam that keeps EventData injection out of the
     * public TimingApplicationRuntime API while reusing this exact composition path.
     */
    static TimingApplicationRuntime createSimulation(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            EventData eventData) {
        return create(
                buildIdentity,
                config,
                antennaInstallations,
                eventData,
                null,
                null);
    }

    private static TimingApplicationRuntime create(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            EventData eventData,
            Reader consoleInput,
            Writer consoleOutput) {
        requireCompositionInput(
                buildIdentity,
                config,
                antennaInstallations,
                eventData);

        List<AntennaInstallation> installations =
                copyInstallations(
                        antennaInstallations);

        /*
         * 1. Resolve runtime configuration and the process-wide platform
         *    environment. No component is active yet.
         */
        PlatformEnvironment platform =
                PlatformEnvironment.system();

        ApplicationConfiguration applicationConfiguration =
                ApplicationConfiguration.singleTimingNode(
                        config.timingNodeId(),
                        config.tagProcessingPolicy());

        /*
         * 2. Construct shared execution resources.
         *    Construction does not start their physical workers.
         */
        RuntimeExecutors executors =
                new RuntimeExecutors();

        try {
            RuntimeExecutors.TimingNodeExecutors nodeExecutors =
                    executors.createTimingNodeExecutors();

            /*
             * 3. Construct Infrastructure and Domain objects.
             */
            TimingDataPersistence persistence =
                    new DefaultTimingDataPersistence(
                            new FileAppendOnlyRecordStore(
                                    config.timingDataPath()),
                            config.timingNodeId(),
                            new DefaultTimingDataCodec());

            TimingNode timingNode =
                    new TimingNode(
                            config.timingNodeId(),
                            persistence,
                            new DefaultTimingDataFactory(),
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

            /*
             * 4. Construct optional I/O.
             */
            AntennaManager antennaManager = null;
            if (!installations.isEmpty()) {
                antennaManager =
                        new AntennaManager(
                                installations,
                                executors
                                        .createAntennaControlExecutor(),
                                ANTENNA_CONTROL_TIMEOUT);
            }

            /*
             * 5. Construct Application-facing coordination/presentation objects.
             */
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
             * 6. Wire the object graph explicitly.
             */
            if (antennaManager != null) {
                timingNode.statusChangedEvent()
                        .subscribe(
                                conductor
                                        ::onTimingNodeStatusChanged);

                for (AntennaInstallation installation
                        : installations) {
                    antennaManager
                            .tagObservedEvent(
                                    installation
                                            .antennaId())
                            .subscribe(
                                    timingNode
                                            .tagProcessor()
                                            ::onTagObserved);
                }
            }

            /*
             * 7. Return the fully constructed and wired graph.
             *
             * Conductor owns TimingNode/AntennaManager lifecycle coordination.
             * Runtime keeps Presentation outside that application-core lifecycle
             * so external adapters start only after the coordinated core is ready.
             */
            return new TimingApplicationRuntime(
                    buildIdentity,
                    timingNode,
                    applicationConfiguration,
                    presentationGateway,
                    executors,
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
     *
     * <p>Runtime starts the shared physical workers first. Conductor then
     * activates and coordinates the application components. Presentation starts
     * last, after the application core has established its current state.</p>
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

    public synchronized State state() {
        return state;
    }

    /**
     * Waits until Presentation or the process requests normal application shutdown.
     */
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
     * Stops outer Presentation first, then lets Conductor deactivate the
     * application components before Runtime closes the shared workers.
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

    private static EventData defaultEventData() {
        return new DefaultEventDataProvider()
                .createEventData();
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
            EventData eventData) {
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
    }
}
