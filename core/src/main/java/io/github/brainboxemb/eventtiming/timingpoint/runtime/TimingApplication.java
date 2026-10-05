package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagRegistrationMapper;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.time.Duration;
import java.time.Instant;
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
 * wires the graph without starting physical application workers. {@link #start()}
 * starts the already composed graph. {@link #close()} stops it.</p>
 *
 * <p>The production path is deliberately readable in one place:</p>
 *
 * <pre>
 * configuration
 *   -> runtime resources
 *   -> Domain / I/O / Application objects
 *   -> explicit event wiring
 *   -> TimingApplication
 * </pre>
 */
public final class TimingApplication implements AutoCloseable {

    private static final Duration ANTENNA_CONTROL_TIMEOUT =
            Duration.ofSeconds(2);

    private final BuildIdentity buildIdentity;
    private final TimingNode timingNode;
    private final ApplicationConfiguration configuration;
    private final PresentationGateway presentationGateway;
    private final Lifecycle lifecycle;
    private final Conductor conductor;
    private final AntennaManager antennaManager;
    private final RuntimeExecutors runtimeExecutors;

    private TimingApplication(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ApplicationConfiguration configuration,
            PresentationGateway presentationGateway,
            Conductor conductor,
            AntennaManager antennaManager,
            RuntimeExecutors runtimeExecutors) {
        this.buildIdentity = buildIdentity;
        this.timingNode = timingNode;
        this.configuration = configuration;
        this.presentationGateway = presentationGateway;
        this.lifecycle = new Lifecycle(buildIdentity);
        this.conductor = conductor;
        this.antennaManager = antennaManager;
        this.runtimeExecutors = runtimeExecutors;
    }

    /**
     * Constructs and wires the normal configured application.
     *
     * <p>No physical application worker is started by this method.</p>
     */
    public static TimingApplication create(
            BuildIdentity buildIdentity,
            Config config) {
        return create(
                buildIdentity,
                config,
                Collections.<AntennaInstallation>emptyList(),
                tagId -> null);
    }

    /**
     * Constructs and wires the same application graph with explicitly supplied
     * antenna installations and tag mapping. Simulation uses this overload rather
     * than a parallel runtime path.
     */
    public static TimingApplication create(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            TagRegistrationMapper tagRegistrationMapper) {
        requireCompositionInput(
                buildIdentity,
                config,
                antennaInstallations,
                tagRegistrationMapper);

        List<AntennaInstallation> installations =
                copyInstallations(
                        antennaInstallations);

        /*
         * 1. Resolve runtime configuration.
         */
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
                    executors.createTimingNodeExecutors(
                            config.timingNodeId());

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
                                    Instant.now()),
                            applicationConfiguration
                                    .timingNode(
                                            config.timingNodeId())
                                    .tagProcessing(),
                            tagRegistrationMapper,
                            nodeExecutors.timingNode(),
                            nodeExecutors.tagProcessor());

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
                                executors.antennaScheduler(),
                                ANTENNA_CONTROL_TIMEOUT);
            }

            /*
             * 5. Construct Application-facing coordination/presentation objects.
             */
            Conductor conductor =
                    new Conductor(
                            antennaManager);

            ConfigurationControl configurationControl =
                    createConfigurationControl(
                            applicationConfiguration);

            PresentationGateway presentationGateway =
                    new PresentationGateway(
                            buildIdentity,
                            timingNode,
                            configurationControl);

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
             * 7. Return the composed graph. start() is a separate phase.
             */
            return new TimingApplication(
                    buildIdentity,
                    timingNode,
                    applicationConfiguration,
                    presentationGateway,
                    conductor,
                    antennaManager,
                    executors);
        } catch (RuntimeException ex) {
            executors.close();
            throw ex;
        }
    }

    /**
     * Starts the already constructed and wired application.
     */
    public void start() {
        boolean timingNodeStarted = false;
        boolean antennaManagerStarted = false;

        try {
            runtimeExecutors.start();

            timingNode.start();
            timingNodeStarted = true;

            if (antennaManager != null) {
                antennaManager.start();
                antennaManagerStarted = true;

                conductor.onTimingNodeStatusChanged(
                        timingNode.query(
                                TimingNodeQueries.status()));
            }

            lifecycle.start();
        } catch (RuntimeException ex) {
            if (antennaManagerStarted) {
                try {
                    antennaManager.close();
                } catch (RuntimeException ignored) {
                    // Preserve the original startup failure.
                }
            }

            if (timingNodeStarted) {
                try {
                    timingNode.stop();
                } catch (RuntimeException ignored) {
                    // Preserve the original startup failure.
                }
            }

            runtimeExecutors.close();
            throw ex;
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

    BuildIdentity buildIdentity() {
        return buildIdentity;
    }

    Lifecycle.State state() {
        return lifecycle.state();
    }

    public void awaitStopped()
            throws InterruptedException {
        lifecycle.awaitStopped();
    }

    public String smokeOutput() {
        return smokeOutput(
                buildIdentity,
                lifecycle.state());
    }

    @Override
    public void close() {
        RuntimeException firstFailure = null;

        if (antennaManager != null) {
            try {
                antennaManager.close();
            } catch (RuntimeException ex) {
                firstFailure = ex;
            }
        }

        try {
            timingNode.stop();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }

        runtimeExecutors.close();
        lifecycle.close();

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public static String smokeOutput(
            BuildIdentity buildIdentity,
            Lifecycle.State state) {
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
            TagRegistrationMapper tagRegistrationMapper) {
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
        if (tagRegistrationMapper == null) {
            throw new IllegalArgumentException(
                    "tagRegistrationMapper must not be null");
        }
    }
}
