package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
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
 * <p>This is both the visible composition root and the lifecycle owner of the
 * resulting object graph. {@link #create(BuildIdentity, Config)} constructs and
 * wires the graph without starting physical application workers. {@link #activate()}
 * activates the already composed graph. {@link #deactivate()} deactivates it.</p>
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
    private final ActivationManager activationManager;
    private final ShutdownSignal shutdownSignal;

    private State state = State.NEW;

    private TimingApplicationRuntime(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ApplicationConfiguration configuration,
            PresentationGateway presentationGateway,
            RuntimeExecutors runtimeExecutors,
            ActivationManager activationManager,
            ShutdownSignal shutdownSignal) {
        this.buildIdentity = buildIdentity;
        this.timingNode = timingNode;
        this.configuration = configuration;
        this.presentationGateway = presentationGateway;
        this.runtimeExecutors = runtimeExecutors;
        this.activationManager = activationManager;
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
                EventData.empty(),
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
                EventData.empty(),
                consoleInput,
                consoleOutput);
    }

    /**
     * Constructs and wires the same application graph with explicitly supplied
     * antenna installations and event data. Simulation uses this overload rather
     * than a parallel runtime path.
     */
    public static TimingApplicationRuntime create(
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
            Conductor conductor = null;
            if (antennaManager != null) {
                conductor =
                        new Conductor(
                                timingNode,
                                antennaManager,
                                executors
                                        .createConductorExecutor());
            }

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
            if (conductor != null) {
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
             * 7. Define component activation order explicitly.
             *
             * Registration order is activation order; ActivationManager
             * deactivates successful components in reverse order.
             */
            ActivationManager activation =
                    new ActivationManager();
            activation.register(
                    timingNode::activate,
                    timingNode::deactivate);
            if (antennaManager != null) {
                activation.register(
                        antennaManager::activate,
                        antennaManager::deactivate);
            }
            if (conductor != null) {
                activation.register(
                        conductor::activate,
                        conductor::deactivate);
            }
            activation.register(
                    presentation::activate,
                    presentation::deactivate);

            /*
             * 8. Return the fully constructed and wired graph.
             *    Activation remains a separate phase.
             */
            return new TimingApplicationRuntime(
                    buildIdentity,
                    timingNode,
                    applicationConfiguration,
                    presentationGateway,
                    executors,
                    activation,
                    shutdownSignal);
        } catch (RuntimeException | Error failure) {
            executors.close();
            throw failure;
        }
    }

    /**
     * Activates the already constructed and wired application.
     *
     * <p>Runtime execution infrastructure starts first. Application components
     * then activate in their registration order through ActivationManager.</p>
     */
    public synchronized void activate() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "TimingApplicationRuntime can only activate from NEW; current state="
                            + state);
        }

        try {
            runtimeExecutors.start();
            activationManager.activateAll();
            state = State.ACTIVE;
        } catch (RuntimeException | Error failure) {
            try {
                activationManager.deactivateAll();
            } catch (RuntimeException | Error deactivateFailure) {
                failure.addSuppressed(deactivateFailure);
            }
            try {
                runtimeExecutors.close();
            } catch (RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
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
     * Deactivates application components in reverse order and then closes the
     * Runtime-owned execution infrastructure.
     */
    public synchronized void deactivate() {
        shutdownSignal.request();

        if (state == State.INACTIVE) {
            return;
        }

        Throwable firstFailure = null;

        try {
            activationManager.deactivateAll();
        } catch (RuntimeException | Error failure) {
            firstFailure = failure;
        }

        try {
            runtimeExecutors.close();
        } catch (RuntimeException | Error failure) {
            if (firstFailure == null) {
                firstFailure = failure;
            } else {
                firstFailure.addSuppressed(failure);
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
