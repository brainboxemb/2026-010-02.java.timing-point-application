package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

/**
 * Shared transport-independent application gateway for Presentation.
 *
 * <p>The gateway exposes application-wide presentation information and gives adapters
 * access to node-scoped application proxies. Presentation adapters do not call
 * TimingNode directly.</p>
 */
public final class PresentationGateway {
    /** First Step-4 engineering capability set. */
    public static final class Capabilities {
        private final boolean directRegistrationSimulationSupported;
        private final boolean directRegistrationSimulationEnabled;

        private Capabilities(
                boolean directRegistrationSimulationSupported,
                boolean directRegistrationSimulationEnabled) {
            this.directRegistrationSimulationSupported =
                    directRegistrationSimulationSupported;
            this.directRegistrationSimulationEnabled =
                    directRegistrationSimulationEnabled;
        }

        public boolean directRegistrationSimulationSupported() {
            return directRegistrationSimulationSupported;
        }

        public boolean directRegistrationSimulationEnabled() {
            return directRegistrationSimulationEnabled;
        }
    }

    private static final Capabilities STEP4_CAPABILITIES =
            new Capabilities(true, true);

    private final BuildIdentity buildIdentity;
    private final TimingNodeProxy timingNode;
    private final ConfigurationControl configuration;

    /** Creates the presentation-facing application gateway for the composed application. */
    public PresentationGateway(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ConfigurationControl configuration) {
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
        this.timingNode = new TimingNodeProxy(timingNode);
        this.configuration = configuration;
    }

    /** Returns the authoritative application build/version identity. */
    public BuildIdentity version() {
        return buildIdentity;
    }

    /** Returns the engineering capabilities for the current composition. */
    public Capabilities capabilities() {
        return STEP4_CAPABILITIES;
    }

    /** Returns the presentation-facing proxy for the currently composed TimingNode. */
    public TimingNodeProxy timingNode() {
        return timingNode;
    }

    /** Application configuration query/update boundary exposed to Presentation. */
    public ConfigurationControl configuration() {
        return configuration;
    }
}
