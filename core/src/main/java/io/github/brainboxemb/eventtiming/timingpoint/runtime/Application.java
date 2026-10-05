package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.configuration.ApplicationConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

/** Top-level runtime object for one SI-01 application composition. */
public final class Application implements AutoCloseable {
    private final BuildIdentity buildIdentity;
    private final TimingNode timingNode;
    private final ApplicationConfiguration configuration;
    private final PresentationGateway presentationGateway;
    private final Lifecycle lifecycle;
    private final AntennaRuntime antennaRuntime;

    Application(BuildIdentity buildIdentity, TimingNode timingNode) {
        this(
                buildIdentity,
                timingNode,
                ApplicationConfiguration.singleTimingNode(
                        timingNode.timingNodeId(),
                        TagProcessingPolicy.defaults()),
                null);
    }

    Application(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            AntennaRuntime antennaRuntime) {
        this(
                buildIdentity,
                timingNode,
                ApplicationConfiguration.singleTimingNode(
                        timingNode.timingNodeId(),
                        TagProcessingPolicy.defaults()),
                antennaRuntime);
    }

    Application(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ApplicationConfiguration configuration,
            AntennaRuntime antennaRuntime) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException("buildIdentity must not be null");
        }
        if (timingNode == null) {
            throw new IllegalArgumentException("timingNode must not be null");
        }
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        this.buildIdentity = buildIdentity;
        this.timingNode = timingNode;
        this.configuration = configuration;
        this.presentationGateway = new PresentationGateway(buildIdentity, timingNode);
        this.lifecycle = new Lifecycle(buildIdentity);
        this.antennaRuntime = antennaRuntime;
    }

    public void start() {
        timingNode.start();
        try {
            if (antennaRuntime != null) {
                antennaRuntime.start();
            }
            lifecycle.start();
        } catch (RuntimeException ex) {
            if (antennaRuntime != null) {
                try {
                    antennaRuntime.close();
                } catch (RuntimeException ignored) {
                    // Preserve the startup failure.
                }
            }
            timingNode.stop();
            throw ex;
        }
    }

    public PresentationGateway presentationGateway() {
        return presentationGateway;
    }

    /** Authoritative typed configuration root for this running application. */
    public ApplicationConfiguration configuration() {
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

        if (antennaRuntime != null) {
            try {
                antennaRuntime.close();
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

        lifecycle.close();

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    public static String smokeOutput(BuildIdentity buildIdentity, Lifecycle.State state) {
        return buildIdentity.application()
                + " lifecycle OK version="
                + buildIdentity.version()
                + " state="
                + state;
    }
}
