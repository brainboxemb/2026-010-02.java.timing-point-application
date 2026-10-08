package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
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

    private final List<TimingNodeConfig> timingNodes;
    private final Presentation presentation;
    private final LoggingConfig logging;
    private final LoggingServerConfig loggingServer;
    private final String eventDataProviderId;
    private final String timingDataProviderId;

    public Config(NodeId timingNodeId, Presentation presentation) {
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
                oneTimingNodeConfig(
                        timingNodeId,
                        timingDataPath,
                        tagProcessingPolicy),
                presentation,
                logging,
                loggingServer,
                eventDataProviderId,
                timingDataProviderId);
    }

    public Config(
            List<TimingNodeConfig> timingNodes,
            Presentation presentation,
            LoggingConfig logging,
            LoggingServerConfig loggingServer,
            String eventDataProviderId,
            String timingDataProviderId) {
        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "timingNodes must contain at least one TimingNode");
        }
        if (presentation == null) {
            throw new IllegalArgumentException(
                    "presentation must not be null");
        }

        List<TimingNodeConfig> nodes =
                new ArrayList<TimingNodeConfig>(timingNodes.size());
        Set<NodeId> nodeIds =
                new LinkedHashSet<NodeId>();
        Set<Path> storagePaths =
                new LinkedHashSet<Path>();

        for (TimingNodeConfig timingNode : timingNodes) {
            if (timingNode == null) {
                throw new IllegalArgumentException(
                        "timingNodes must not contain null");
            }
            if (!nodeIds.add(timingNode.timingNodeId())) {
                throw new IllegalArgumentException(
                        "Duplicate TimingNode id "
                                + timingNode.timingNodeId().value());
            }

            Path storagePath =
                    timingNode.timingDataPath();
            if (storagePath != null) {
                Path normalized =
                        storagePath.toAbsolutePath().normalize();
                if (!storagePaths.add(normalized)) {
                    throw new IllegalArgumentException(
                            "Duplicate TimingData storage path "
                                    + storagePath);
                }
            }
            nodes.add(timingNode);
        }

        this.timingNodes =
                Collections.unmodifiableList(nodes);
        this.presentation = presentation;
        this.logging = logging;
        this.loggingServer = loggingServer;
        this.eventDataProviderId =
                requireProviderId(
                        eventDataProviderId,
                        "eventDataProviderId");
        this.timingDataProviderId =
                requireProviderId(
                        timingDataProviderId,
                        "timingDataProviderId");
    }

    public List<TimingNodeConfig> timingNodes() {
        return timingNodes;
    }

    public TimingNodeConfig timingNode(
            NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException(
                    "nodeId must not be null");
        }
        for (TimingNodeConfig timingNode : timingNodes) {
            if (timingNode.timingNodeId().equals(nodeId)) {
                return timingNode;
            }
        }
        throw new IllegalArgumentException(
                "Unknown TimingNode configuration "
                        + nodeId.value());
    }

    /**
     * Single-node compatibility accessor.
     *
     * <p>Multi-node callers must use {@link #timingNodes()} or
     * {@link #timingNode(NodeId)}.</p>
     */
    public NodeId timingNodeId() {
        return requireSingleTimingNode().timingNodeId();
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
        return requireSingleTimingNode().timingDataPath();
    }

    /** Single-node compatibility accessor. */
    public TagProcessingPolicy tagProcessingPolicy() {
        return requireSingleTimingNode().tagProcessingPolicy();
    }

    public String eventDataProviderId() {
        return eventDataProviderId;
    }

    public String timingDataProviderId() {
        return timingDataProviderId;
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

    private TimingNodeConfig requireSingleTimingNode() {
        if (timingNodes.size() != 1) {
            throw new IllegalStateException(
                    "Operation requires exactly one TimingNode; configured="
                            + timingNodes.size());
        }
        return timingNodes.get(0);
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
