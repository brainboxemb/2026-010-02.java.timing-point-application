package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
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

    private static final String DEFAULT_TIMING_SYSTEM_ID =
            "timing-system-01";

    /** Effective startup configuration for one TimingNode. */
    public static final class TimingNodeConfig {
        private final NodeId timingNodeId;
        private final Path timingDataPath;
        private final TagProcessingPolicy tagProcessingPolicy;

        public TimingNodeConfig(
                NodeId timingNodeId,
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
            this.timingDataPath = timingDataPath;
            this.tagProcessingPolicy = tagProcessingPolicy;
        }

        public NodeId timingNodeId() {
            return timingNodeId;
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
                    || timingSystemId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "timingSystemId must not be blank");
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

            this.timingSystemId =
                    timingSystemId.trim();
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
    private final Presentation presentation;
    private final LoggingConfig logging;
    private final LoggingServerConfig loggingServer;

    public Config(
            NodeId timingNodeId,
            Presentation presentation) {
        this(
                timingNodeId,
                presentation,
                null,
                null,
                null,
                TagProcessingPolicy.defaults(),
                REFERENCE_PROVIDER_ID,
                REFERENCE_PROVIDER_ID);
    }

    public Config(
            NodeId timingNodeId,
            Presentation presentation,
            LoggingConfig logging) {
        this(
                timingNodeId,
                presentation,
                logging,
                null,
                null,
                TagProcessingPolicy.defaults(),
                REFERENCE_PROVIDER_ID,
                REFERENCE_PROVIDER_ID);
    }

    public Config(
            NodeId timingNodeId,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer) {
        this(
                timingNodeId,
                presentation,
                logging,
                loggingServer,
                null,
                TagProcessingPolicy.defaults(),
                REFERENCE_PROVIDER_ID,
                REFERENCE_PROVIDER_ID);
    }

    public Config(
            NodeId timingNodeId,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer,
            Path timingDataPath) {
        this(
                timingNodeId,
                presentation,
                logging,
                loggingServer,
                timingDataPath,
                TagProcessingPolicy.defaults(),
                REFERENCE_PROVIDER_ID,
                REFERENCE_PROVIDER_ID);
    }

    public Config(
            NodeId timingNodeId,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer,
            Path timingDataPath,
            TagProcessingPolicy tagProcessingPolicy) {
        this(
                timingNodeId,
                presentation,
                logging,
                loggingServer,
                timingDataPath,
                tagProcessingPolicy,
                REFERENCE_PROVIDER_ID,
                REFERENCE_PROVIDER_ID);
    }

    public Config(
            NodeId timingNodeId,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer,
            Path timingDataPath,
            TagProcessingPolicy tagProcessingPolicy,
            String eventDataProviderId,
            String timingDataProviderId) {
        this(
                oneTimingSystemConfig(
                        oneTimingNodeConfig(
                                timingNodeId,
                                timingDataPath,
                                tagProcessingPolicy),
                        eventDataProviderId,
                        timingDataProviderId),
                presentation,
                logging,
                loggingServer);
    }

    /**
     * Compatibility constructor for the current one-TimingSystem callers.
     */
    public Config(
            List<TimingNodeConfig> timingNodes,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer,
            String eventDataProviderId,
            String timingDataProviderId) {
        this(
                oneTimingSystemConfig(
                        timingNodes,
                        eventDataProviderId,
                        timingDataProviderId),
                presentation,
                logging,
                loggingServer);
    }

    public Config(
            List<TimingSystemConfig> timingSystems,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer) {
        this(timingSystems, Collections.<AntennaManagerConfig>emptyList(),
                presentation, logging, loggingServer);
    }

    public Config(
            List<TimingSystemConfig> timingSystems,
            List<AntennaManagerConfig> antennaManagers,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer) {
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

    /**
     * Single-node compatibility accessor.
     */
    public NodeId timingNodeId() {
        return requireSingleTimingNode()
                .timingNodeId();
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

    /** Single-node compatibility accessor. */
    public Path timingDataPath() {
        return requireSingleTimingNode()
                .timingDataPath();
    }

    /** Single-node compatibility accessor. */
    public TagProcessingPolicy tagProcessingPolicy() {
        return requireSingleTimingNode()
                .tagProcessingPolicy();
    }

    /** Single-system compatibility accessor. */
    public String eventDataProviderId() {
        return requireSingleTimingSystem()
                .eventDataProviderId();
    }

    /** Single-system compatibility accessor. */
    public String timingDataProviderId() {
        return requireSingleTimingSystem()
                .timingDataProviderId();
    }

    private static List<TimingNodeConfig> oneTimingNodeConfig(
            NodeId timingNodeId,
            Path timingDataPath,
            TagProcessingPolicy tagProcessingPolicy) {
        List<TimingNodeConfig> result =
                new ArrayList<TimingNodeConfig>();
        result.add(
                new TimingNodeConfig(
                        timingNodeId,
                        timingDataPath,
                        tagProcessingPolicy));
        return result;
    }

    private static List<TimingSystemConfig> oneTimingSystemConfig(
            List<TimingNodeConfig> timingNodes,
            String eventDataProviderId,
            String timingDataProviderId) {
        List<TimingSystemConfig> result =
                new ArrayList<TimingSystemConfig>();
        result.add(
                new TimingSystemConfig(
                        DEFAULT_TIMING_SYSTEM_ID,
                        timingNodes,
                        eventDataProviderId,
                        timingDataProviderId));
        return result;
    }

    private TimingNodeConfig requireSingleTimingNode() {
        List<TimingNodeConfig> nodes =
                timingSystems.timingNodes();
        if (nodes.size() != 1) {
            throw new IllegalStateException(
                    "Operation requires exactly one TimingNode; configured="
                            + nodes.size());
        }
        return nodes.get(0);
    }

    private TimingSystemConfig requireSingleTimingSystem() {
        List<TimingSystemConfig> systems =
                timingSystems.systems();
        if (systems.size() != 1) {
            throw new IllegalStateException(
                    "Operation requires exactly one TimingSystem; configured="
                            + systems.size());
        }
        return systems.get(0);
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
