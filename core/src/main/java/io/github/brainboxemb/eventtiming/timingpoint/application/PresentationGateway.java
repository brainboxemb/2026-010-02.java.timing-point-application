package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final Map<NodeId, TimingNodeProxy> timingNodes;
    private final List<TimingNodeProxy> timingNodeList;
    private final ConfigurationControl configuration;
    private final SimulationControl simulation;
    private final Capabilities capabilities;

    /** Creates the presentation-facing gateway for all composed TimingNodes. */
    public PresentationGateway(
            BuildIdentity buildIdentity,
            List<TimingNode> timingNodes,
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
            List<TimingNode> timingNodes,
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

        Map<NodeId, TimingNodeProxy> proxies =
                new LinkedHashMap<NodeId, TimingNodeProxy>();
        for (TimingNode timingNode : timingNodes) {
            if (timingNode == null) {
                throw new IllegalArgumentException(
                        "timingNodes must not contain null");
            }

            NodeId nodeId = timingNode.timingNodeId();
            TimingNodeProxy previous =
                    proxies.put(
                            nodeId,
                            new TimingNodeProxy(timingNode));
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate TimingNode id "
                                + nodeId.value());
            }
        }

        this.buildIdentity = buildIdentity;
        this.timingNodes =
                Collections.unmodifiableMap(proxies);
        this.timingNodeList =
                Collections.unmodifiableList(
                        new ArrayList<TimingNodeProxy>(
                                proxies.values()));
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
        return timingNodeList;
    }

    /** Returns current status for every composed TimingNode. */
    public List<TimingNodeStatus> timingNodeStatuses() {
        List<TimingNodeStatus> statuses =
                new ArrayList<TimingNodeStatus>(
                        timingNodeList.size());
        for (TimingNodeProxy timingNode : timingNodeList) {
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
