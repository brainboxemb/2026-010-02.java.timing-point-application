package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared transport-independent application gateway for Presentation.
 *
 * <p>The gateway exposes application-wide presentation information and one
 * {@link TimingNodeProxy} for every composed TimingNode. Presentation adapters
 * address a node by its application-wide NodeId and do not call TimingNode
 * directly.</p>
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
    private final TimingNodeProxyRegistry timingNodes;
    private final ConfigurationControl configuration;
    private final SimulationControl simulation;
    private final Capabilities capabilities;

    /** Creates the presentation-facing gateway for all composed TimingNodes. */
    public PresentationGateway(
            BuildIdentity buildIdentity,
            TimingNodeList timingNodes,
            ConfigurationControl configuration) {
        this(
                buildIdentity,
                timingNodes,
                configuration,
                null);
    }

    /** Creates the gateway with optional engineering simulated-tag control. */
    public PresentationGateway(
            BuildIdentity buildIdentity,
            TimingNodeList timingNodes,
            ConfigurationControl configuration,
            SimulationControl simulation) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }
        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "timingNodes must not be empty");
        }
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "configuration must not be null");
        }

        this.buildIdentity = buildIdentity;
        this.timingNodes =
                new TimingNodeProxyRegistry(
                        timingNodes);
        this.configuration = configuration;
        this.simulation = simulation;
        this.capabilities =
                new Capabilities(
                        true,
                        true,
                        true,
                        simulation != null);
    }

    /** Returns the application build/version identity. */
    public BuildIdentity version() {
        return buildIdentity;
    }

    /** Returns the engineering capabilities for the current composition. */
    public Capabilities capabilities() {
        return capabilities;
    }

    /** Returns the optional simulated-tag control. */
    public SimulationControl simulation() {
        if (simulation == null) {
            throw new IllegalStateException(
                    "Simulated-tag control is not enabled");
        }
        return simulation;
    }

    /** Returns one proxy by application-wide TimingNode id. */
    public TimingNodeProxy timingNode(
            NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException(
                    "nodeId must not be null");
        }

        TimingNodeProxy proxy =
                timingNodes.get(nodeId);
        if (proxy == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingNode "
                            + nodeId.value());
        }
        return proxy;
    }

    /** Returns all node proxies in composition order. */
    public List<TimingNodeProxy> timingNodes() {
        return timingNodes.values();
    }

    /** Returns current status for every composed TimingNode. */
    public List<TimingNodeStatus> timingNodeStatuses() {
        List<TimingNodeStatus> statuses =
                new ArrayList<TimingNodeStatus>(
                        timingNodes.size());
        for (TimingNodeProxy timingNode : timingNodes) {
            statuses.add(
                    timingNode.status());
        }
        return Collections.unmodifiableList(statuses);
    }

    /** Application configuration query/update boundary exposed to Presentation. */
    public ConfigurationControl configuration() {
        return configuration;
    }
}
