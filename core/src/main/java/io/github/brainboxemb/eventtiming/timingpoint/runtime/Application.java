package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
    private final PresentationGateway presentationGateway;
    private final Lifecycle lifecycle;
    private final AntennaManager antennaManager;
    private final RuntimeExecutors runtimeExecutors;

    private final Consumer<TagObservation> observationListener;
    private final Consumer<Status> timingNodeStatusListener;
    private final List<Antenna> subscribedAntennas =
            new ArrayList<Antenna>();

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
                null,
                null);
    }

    Application(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ApplicationConfiguration configuration,
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
        if (antennaManager != null && runtimeExecutors == null) {
            throw new IllegalArgumentException(
                    "antennaManager requires runtimeExecutors");
        }

        this.buildIdentity = buildIdentity;
        this.timingNode = timingNode;
        this.configuration = configuration;
        this.presentationGateway =
                new PresentationGateway(buildIdentity, timingNode);
        this.lifecycle = new Lifecycle(buildIdentity);
        this.antennaManager = antennaManager;
        this.runtimeExecutors = runtimeExecutors;

        if (antennaManager == null) {
            observationListener = null;
            timingNodeStatusListener = null;
        } else {
            /*
             * These are wiring callbacks only. Event delivery enters the owning
             * component immediately and returns; potentially blocking device work
             * is submitted by AntennaManager to the runtime-owned shared I/O pool.
             */
            observationListener =
                    timingNode.tagProcessor()::onObservation;
            timingNodeStatusListener =
                    status -> antennaManager.requestOperational(
                            status.lifecycle()
                                    == io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle.OPEN);
        }
    }

    /**
     * Starts component ownership from the inside out.
     *
     * <p>TimingNode starts its node and TagProcessor lanes first. AntennaManager
     * then performs independent startup probes. Normal inventory follows the
     * TimingNode OPEN/CLOSED event and therefore cannot begin before the node
     * processing path exists.</p>
     */
    public void start() {
        timingNode.start();
        boolean antennaWiringInstalled = false;

        try {
            if (antennaManager != null) {
                subscribeAntennaPath();
                antennaWiringInstalled = true;
                antennaManager.start();

                Status current = timingNode.query(
                        TimingNodeQueries.status());
                antennaManager.requestOperational(
                        current.lifecycle()
                                == io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle.OPEN);
            }

            lifecycle.start();
        } catch (RuntimeException ex) {
            if (antennaManager != null) {
                try {
                    antennaManager.close();
                } catch (RuntimeException ignored) {
                    // Preserve the original startup failure.
                }
            }
            if (antennaWiringInstalled) {
                unsubscribeAntennaPath();
            }
            try {
                timingNode.stop();
            } finally {
                if (runtimeExecutors != null) {
                    runtimeExecutors.close();
                }
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
         * Stop new lifecycle requests before device shutdown. AntennaManager then
         * stops inventory/power and closes providers before observation listeners
         * are detached.
         */
        if (antennaManager != null) {
            timingNode.statusChangedEvent()
                    .unsubscribe(timingNodeStatusListener);
            try {
                antennaManager.close();
            } catch (RuntimeException ex) {
                firstFailure = ex;
            }
            unsubscribeObservationListeners();
        }

        /*
         * TimingNode stops TagProcessor before its own serial lane, allowing
         * already admitted tag work to finish its TimingNode.offer(...) handoff.
         */
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

    private void subscribeAntennaPath() {
        if (!timingNode.statusChangedEvent()
                .subscribe(timingNodeStatusListener)) {
            throw new IllegalStateException(
                    "Antenna lifecycle listener was already subscribed");
        }

        try {
            for (Antenna antenna : antennaManager.antennas()) {
                if (!antenna.observations()
                        .subscribe(observationListener)) {
                    throw new IllegalStateException(
                            "TagProcessor observation listener was already subscribed");
                }
                subscribedAntennas.add(antenna);
            }
        } catch (RuntimeException ex) {
            unsubscribeAntennaPath();
            throw ex;
        }
    }

    private void unsubscribeAntennaPath() {
        if (timingNodeStatusListener != null) {
            timingNode.statusChangedEvent()
                    .unsubscribe(timingNodeStatusListener);
        }
        unsubscribeObservationListeners();
    }

    private void unsubscribeObservationListeners() {
        if (observationListener == null) {
            return;
        }
        for (Antenna antenna : subscribedAntennas) {
            antenna.observations().unsubscribe(observationListener);
        }
        subscribedAntennas.clear();
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
