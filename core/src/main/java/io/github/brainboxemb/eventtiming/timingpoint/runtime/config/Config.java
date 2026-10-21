package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingConfig;
import io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Effective configuration consumed by the runtime composition. */
public final class Config {
    /** Built-in IF-11 provider selection used when deployment does not override it. */
    public static final String REFERENCE_PROVIDER_ID = "reference";

    /** Effective startup configuration for one TimingNode. */
    public static final class TimingNodeConfig {
        private final NodeId timingNodeId;
        private final AntennaId antennaId;
        private final Path timingDataPath;
        private final TagProcessingPolicy tagProcessingPolicy;

        /** Creates a node configuration whose antenna is resolved during composition. */
        public TimingNodeConfig(
                NodeId timingNodeId,
                Path timingDataPath,
                TagProcessingPolicy tagProcessingPolicy) {
            this(timingNodeId, null, timingDataPath, tagProcessingPolicy);
        }

        public TimingNodeConfig(
                NodeId timingNodeId,
                AntennaId antennaId,
                Path timingDataPath,
                TagProcessingPolicy tagProcessingPolicy) {
            if (timingNodeId == null) {
                throw new IllegalArgumentException(
                        "timingNodeId must not be null");
            }
            if (timingDataPath != null
                    && timingDataPath.toString().trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "timingDataPath must not be empty");
            }
            if (tagProcessingPolicy == null) {
                throw new IllegalArgumentException(
                        "tagProcessingPolicy must not be null");
            }

            this.timingNodeId = timingNodeId;
            this.antennaId = antennaId;
            this.timingDataPath = timingDataPath;
            this.tagProcessingPolicy = tagProcessingPolicy;
        }

        public NodeId timingNodeId() {
            return timingNodeId;
        }

        public AntennaId antennaId() {
            return antennaId;
        }

        public Path timingDataPath() {
            return timingDataPath;
        }

        public TagProcessingPolicy tagProcessingPolicy() {
            return tagProcessingPolicy;
        }
    }

    /** Effective startup configuration for one TimingSystem. */
    public static final class TimingSystemConfig {
        private final String timingSystemId;
        private final List<TimingNodeConfig> timingNodes;
        private final String eventDataProviderId;
        private final String timingDataProviderId;

        public TimingSystemConfig(
                String timingSystemId,
                List<TimingNodeConfig> timingNodes,
                String eventDataProviderId,
                String timingDataProviderId) {
            if (timingSystemId == null
                    || !timingSystemId.trim().matches("[A-Z1-9]")) {
                throw new IllegalArgumentException(
                        "TimingSystemId must be one character A-Z or 1-9");
            }
            if (timingNodes == null
                    || timingNodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "timingNodes must contain at least one TimingNode");
            }

            List<TimingNodeConfig> nodes =
                    new ArrayList<TimingNodeConfig>(
                            timingNodes.size());
            Set<NodeId> nodeIds =
                    new LinkedHashSet<NodeId>();

            for (TimingNodeConfig timingNode : timingNodes) {
                if (timingNode == null) {
                    throw new IllegalArgumentException(
                            "timingNodes must not contain null");
                }
                if (!nodeIds.add(
                        timingNode.timingNodeId())) {
                    throw new IllegalArgumentException(
                            "Duplicate TimingNode id "
                                    + timingNode
                                            .timingNodeId()
                                            .value()
                                    + " in TimingSystem "
                                    + timingSystemId);
                }
                nodes.add(timingNode);
            }

            String resolvedSystemId = timingSystemId.trim();
            if (nodes.size() == 1) {
                String nodeId = nodes.get(0).timingNodeId().value();
                if (!resolvedSystemId.equals(nodeId)) {
                    throw new IllegalArgumentException(
                            "Single-node TimingSystemId must equal TimingNodeId "
                                    + nodeId);
                }
            } else if (nodeIds.contains(new NodeId(resolvedSystemId))) {
                throw new IllegalArgumentException(
                        "Multi-node TimingSystemId " + resolvedSystemId
                                + " must differ from its TimingNodeIds");
            }

            this.timingSystemId = resolvedSystemId;
            this.timingNodes =
                    Collections.unmodifiableList(nodes);
            this.eventDataProviderId =
                    requireProviderId(
                            eventDataProviderId,
                            "eventDataProviderId");
            this.timingDataProviderId =
                    requireProviderId(
                            timingDataProviderId,
                            "timingDataProviderId");
        }

        public String timingSystemId() {
            return timingSystemId;
        }

        public List<TimingNodeConfig> timingNodes() {
            return timingNodes;
        }

        public String eventDataProviderId() {
            return eventDataProviderId;
        }

        public String timingDataProviderId() {
            return timingDataProviderId;
        }
    }

    private final TimingSystemConfigRegistry timingSystems;
    private final AntennaManagerConfigRegistry antennaManagers;
    private final UpstreamConfig upstream;
    private final Presentation presentation;
    private final LoggingConfig logging;
    private final LoggingServerConfig loggingServer;

    public Config(
            List<TimingSystemConfig> timingSystems,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer) {
        this(timingSystems, Collections.<AntennaManagerConfig>emptyList(),
                defaultUpstream(),
                presentation, logging, loggingServer);
    }

    public Config(
            List<TimingSystemConfig> timingSystems,
            List<AntennaManagerConfig> antennaManagers,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer) {
        this(
                timingSystems,
                antennaManagers,
                defaultUpstream(),
                presentation,
                logging,
                loggingServer);
    }

    public Config(
            List<TimingSystemConfig> timingSystems,
            List<AntennaManagerConfig> antennaManagers,
            UpstreamConfig upstream,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer) {
        if (upstream == null) {
            throw new IllegalArgumentException(
                    "upstream must not be null");
        }
        if (presentation == null) {
            throw new IllegalArgumentException(
                    "presentation must not be null");
        }

        TimingSystemConfigRegistry systems =
                new TimingSystemConfigRegistry(
                        timingSystems);
        AntennaManagerConfigRegistry antennaBindings =
                new AntennaManagerConfigRegistry(
                        antennaManagers,
                        systems);

        this.antennaManagers = antennaBindings;
        this.timingSystems = systems;
        this.upstream = upstream;
        this.presentation = presentation;
        this.logging = logging;
        this.loggingServer = loggingServer;
    }

    public List<TimingSystemConfig> timingSystems() {
        return timingSystems.systems();
    }

    public List<AntennaManagerConfig> antennaManagers() {
        return antennaManagers.bindings();
    }

    /** An absent binding means this system has no configured AntennaManager. */
    public AntennaManagerConfig antennaManager(String timingSystemId) {
        return antennaManagers.binding(
                timingSystemId);
    }

    public TimingSystemConfig timingSystem(
            String timingSystemId) {
        return timingSystems.system(
                timingSystemId);
    }

    public List<TimingNodeConfig> timingNodes() {
        return timingSystems.timingNodes();
    }

    public TimingNodeConfig timingNode(
            NodeId nodeId) {
        return timingSystems.timingNode(
                nodeId);
    }

    public UpstreamConfig upstream() {
        return upstream;
    }

    public Presentation presentation() {
        return presentation;
    }

    public LoggingConfig logging() {
        return logging;
    }

    public LoggingServerConfig loggingServer() {
        return loggingServer;
    }

    private static UpstreamConfig defaultUpstream() {
        return new UpstreamConfig(
                UpstreamConfig.Transport.ENGINEERING_API);
    }

    private static String requireProviderId(
            String value,
            String field) {
        if (value == null
                || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return value.trim();
    }
}
