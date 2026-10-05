package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Top-level runtime object for one SI-01 application composition.
 *
 * <p>Application owns lifecycle coordination of the already constructed component
 * graph. It does not implement antenna protocol, tag processing or TimingNode
 * domain behaviour. Those responsibilities remain in AntennaManager, TagProcessor
 * and TimingNode respectively.</p>
 */
public final class Application implements AutoCloseable {
    private final BuildIdentity buildIdentity;
    private final TimingNode timingNode;
    private final ApplicationConfiguration configuration;
    private final ConfigurationControl configurationControl;
    private final PresentationGateway presentationGateway;
    private final Lifecycle lifecycle;
    private final Conductor conductor;
    private final AntennaManager antennaManager;
    private final RuntimeExecutors runtimeExecutors;

    /**
     * Package-private unit-test seam for Application lifecycle tests.
     *
     * <p>Production objects are built by ApplicationBootstrap so executor and I/O
     * ownership remains explicit at one composition point.</p>
     */
    Application(BuildIdentity buildIdentity, TimingNode timingNode) {
        this(
                buildIdentity,
                timingNode,
                defaultConfiguration(timingNode),
                new Conductor(timingNode, null),
                null,
                null);
    }

    Application(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ApplicationConfiguration configuration,
            Conductor conductor,
            AntennaManager antennaManager,
            RuntimeExecutors runtimeExecutors) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "configuration must not be null");
        }
        if (conductor == null) {
            throw new IllegalArgumentException(
                    "conductor must not be null");
        }
        if (antennaManager != null && runtimeExecutors == null) {
            throw new IllegalArgumentException(
                    "antennaManager requires runtimeExecutors");
        }

        this.buildIdentity = buildIdentity;
        this.timingNode = timingNode;
        this.configuration = configuration;
        this.configurationControl =
                createConfigurationControl(configuration);
        this.presentationGateway =
                new PresentationGateway(
                        buildIdentity,
                        timingNode,
                        configurationControl);
        this.lifecycle = new Lifecycle(buildIdentity);
        this.conductor = conductor;
        this.antennaManager = antennaManager;
        this.runtimeExecutors = runtimeExecutors;
    }

    /**
     * Starts the already constructed application in an explicit order.
     *
     * <p>The order is intentionally visible here: start Runtime workers, install
     * application wiring, start Domain/I/O components, synchronize their current
     * state, then publish the application lifecycle as RUNNING.</p>
     */
    public void start() {
        boolean conductorConnected = false;
        boolean timingNodeStarted = false;
        boolean antennaManagerStarted = false;

        try {
            if (runtimeExecutors != null) {
                runtimeExecutors.start();
            }

            conductor.connect();
            conductorConnected = true;

            timingNode.start();
            timingNodeStarted = true;

            if (antennaManager != null) {
                antennaManager.start();
                antennaManagerStarted = true;
                conductor.synchronize();
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

            if (conductorConnected) {
                conductor.close();
            }

            if (runtimeExecutors != null) {
                runtimeExecutors.close();
            }
            throw ex;
        }
    }

    public PresentationGateway presentationGateway() {
        return presentationGateway;
    }

    /** Typed configuration root for this running application. */
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

    public void awaitStopped() throws InterruptedException {
        lifecycle.awaitStopped();
    }

    public String smokeOutput() {
        return smokeOutput(buildIdentity, lifecycle.state());
    }

    @Override
    public void close() {
        RuntimeException firstFailure = null;

        /*
         * Disconnect application-level relationships first so shutdown does not
         * create new cross-component work while owned components are stopping.
         */
        conductor.close();

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

        if (runtimeExecutors != null) {
            runtimeExecutors.close();
        }

        lifecycle.close();

        if (firstFailure != null) {
            throw firstFailure;
        }
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
                    configuration.timingNode(nodeId).tagProcessing());
        }
        return new ConfigurationControl(tagProcessing);
    }

    private static ApplicationConfiguration defaultConfiguration(
            TimingNode timingNode) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        return ApplicationConfiguration.singleTimingNode(
                timingNode.timingNodeId(),
                TagProcessingPolicy.defaults());
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
}
