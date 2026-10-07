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
    /** Engineering capability set for the current composed application. */
    public static final class Capabilities {
        private final boolean directRegistrationSimulationSupported;
        private final boolean directRegistrationSimulationEnabled;
        private final boolean tagScenarioSimulationSupported;
        private final boolean tagScenarioSimulationEnabled;

        private Capabilities(
                boolean directRegistrationSimulationSupported,
                boolean directRegistrationSimulationEnabled,
                boolean tagScenarioSimulationSupported,
                boolean tagScenarioSimulationEnabled) {
            this.directRegistrationSimulationSupported =
                    directRegistrationSimulationSupported;
            this.directRegistrationSimulationEnabled =
                    directRegistrationSimulationEnabled;
            this.tagScenarioSimulationSupported =
                    tagScenarioSimulationSupported;
            this.tagScenarioSimulationEnabled =
                    tagScenarioSimulationEnabled;
        }

        public boolean directRegistrationSimulationSupported() {
            return directRegistrationSimulationSupported;
        }

        public boolean directRegistrationSimulationEnabled() {
            return directRegistrationSimulationEnabled;
        }

        public boolean tagScenarioSimulationSupported() {
            return tagScenarioSimulationSupported;
        }

        public boolean tagScenarioSimulationEnabled() {
            return tagScenarioSimulationEnabled;
        }
    }

    private final BuildIdentity buildIdentity;
    private final TimingNodeProxy timingNode;
    private final ConfigurationControl configuration;
    private final SimulationControl simulation;
    private final Capabilities capabilities;

    /** Creates the presentation-facing application gateway for the composed application. */
    public PresentationGateway(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ConfigurationControl configuration) {
        this(
                buildIdentity,
                timingNode,
                configuration,
                null);
    }

    /**
     * Creates the gateway with an optional engineering simulated-tag control.
     */
    public PresentationGateway(
            BuildIdentity buildIdentity,
            TimingNode timingNode,
            ConfigurationControl configuration,
            SimulationControl simulation) {
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
        this.simulation = simulation;
        this.capabilities =
                new Capabilities(
                        true,
                        true,
                        true,
                        simulation != null);
    }

    /** Returns the authoritative application build/version identity. */
    public BuildIdentity version() {
        return buildIdentity;
    }

    /** Returns the engineering capabilities for the current composition. */
    public Capabilities capabilities() {
        return capabilities;
    }

    /**
     * Returns the optional simulated-tag control.
     *
     * @throws IllegalStateException when the current composition does not
     *         provide simulated-tag control
     */
    public SimulationControl simulation() {
        if (simulation == null) {
            throw new IllegalStateException(
                    "Simulated-tag control is not enabled");
        }
        return simulation;
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
