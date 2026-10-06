package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingConfig;
import io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerConfig;

import java.nio.file.Path;

/** Effective configuration consumed by the runtime composition. */
public final class Config {
    /** Built-in IF-11 provider selection used when deployment does not override it. */
    public static final String REFERENCE_PROVIDER_ID = "reference";

    private final NodeId timingNodeId;
    private final Presentation presentation;
    private final LoggingConfig logging;
    private final LoggingServerConfig loggingServer;
    private final Path timingDataPath;
    private final TagProcessingPolicy tagProcessingPolicy;
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
        if (timingNodeId == null) {
            throw new IllegalArgumentException(
                    "timingNodeId must not be null");
        }
        if (presentation == null) {
            throw new IllegalArgumentException(
                    "presentation must not be null");
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
        this.presentation = presentation;
        this.logging = logging;
        this.loggingServer = loggingServer;
        this.timingDataPath = timingDataPath;
        this.tagProcessingPolicy = tagProcessingPolicy;
        this.eventDataProviderId =
                requireProviderId(
                        eventDataProviderId,
                        "eventDataProviderId");
        this.timingDataProviderId =
                requireProviderId(
                        timingDataProviderId,
                        "timingDataProviderId");
    }

    public NodeId timingNodeId() {
        return timingNodeId;
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

    public Path timingDataPath() {
        return timingDataPath;
    }

    public TagProcessingPolicy tagProcessingPolicy() {
        return tagProcessingPolicy;
    }

    public String eventDataProviderId() {
        return eventDataProviderId;
    }

    public String timingDataProviderId() {
        return timingDataProviderId;
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
