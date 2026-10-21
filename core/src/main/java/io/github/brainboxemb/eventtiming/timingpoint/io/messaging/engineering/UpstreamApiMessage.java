package io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamMessage;

import java.io.IOException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Engineering-API representation of one encoded IF-06 upstream message.
 *
 * <p>This adapter extracts only the NodeId required by generic Messaging and keeps
 * the complete encoded IF-06 payload unchanged. Semantic decoding remains owned by
 * NodeUpstream.</p>
 */
public final class UpstreamApiMessage {
    private static final JsonFactory JSON = new JsonFactory();

    private final NodeId nodeId;
    private final String payload;

    private UpstreamApiMessage(
            NodeId nodeId,
            String payload) {
        this.nodeId = nodeId;
        this.payload = payload;
    }

    /** Parses only the routing identity from one encoded IF-06 JSON object. */
    public static UpstreamApiMessage parse(
            String payload) {
        failIfInvalidArgument(payload != null, "payload must not be null");

        try (JsonParser parser = JSON.createParser(payload)) {
            failIfInvalidArgument(parser.nextToken() == JsonToken.START_OBJECT,
                    "Upstream API message must be a JSON object");

            String nodeIdValue = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                failIfInvalidArgument(parser.currentToken() == JsonToken.FIELD_NAME,
                        "Invalid Upstream API JSON object");

                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                failIfInvalidArgument(value != null,
                        "Missing value for Upstream API field %s",
                        field);

                if ("nodeId".equals(field)) {
                    failIfInvalidArgument(value == JsonToken.VALUE_STRING,
                            "Upstream API nodeId must be a string");
                    failIfInvalidArgument(nodeIdValue == null,
                            "Upstream API nodeId must occur once");
                    nodeIdValue = parser.getValueAsString();
                } else {
                    parser.skipChildren();
                }
            }

            failIfInvalidArgument(parser.nextToken() == null,
                    "Upstream API message contains trailing JSON content");
            failIfInvalidArgument(nodeIdValue != null,
                    "Upstream API message has no nodeId");

            return new UpstreamApiMessage(
                    new NodeId(nodeIdValue),
                    payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                    "Invalid Upstream API JSON",
                    ex);
        }
    }

    /** Adapts one generic outbound routing envelope to the Engineering API boundary. */
    public static UpstreamApiMessage from(
            UpstreamMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");
        return new UpstreamApiMessage(
                message.nodeId(),
                message.payload());
    }

    /** Returns the routing identity extracted from or supplied with the API message. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Returns the complete encoded IF-06 payload unchanged. */
    public String payload() {
        return payload;
    }

    /** Removes the Engineering-specific wrapper before entering generic Messaging. */
    public UpstreamMessage toUpstreamMessage() {
        return new UpstreamMessage(
                nodeId,
                payload);
    }
}
