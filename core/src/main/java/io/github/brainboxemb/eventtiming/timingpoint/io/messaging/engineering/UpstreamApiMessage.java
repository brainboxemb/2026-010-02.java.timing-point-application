package io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.io.IOException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Engineering-API representation of one encoded IF-06 upstream message.
 *
 * <p>The Engineering boundary needs the NodeId to hand an inbound message to the gateway's transport-side routing
 * port. Generic Messaging does not wrap the payload in another NodeId-bearing message object.</p>
 *
 * <p>Semantic decoding remains owned by NodeUpstream. This adapter extracts only routing identity and preserves the
 * complete encoded IF-06 payload unchanged.</p>
 */
public final class UpstreamApiMessage {
    private static final JsonFactory JSON = new JsonFactory();

    private final NodeId nodeId;
    private final String payload;

    private UpstreamApiMessage(NodeId nodeId, String payload) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(payload != null, "payload must not be null");

        this.nodeId = nodeId;
        this.payload = payload;
    }

    /**
     * Parses only the routing identity from one inbound encoded IF-06 JSON object.
     *
     * <p>The parser deliberately skips all other fields. NodeUpstream later performs the authoritative semantic
     * decode after the gateway has selected the correct application port.</p>
     */
    public static UpstreamApiMessage parse(String payload) {
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
                failIfInvalidArgument(value != null, "Missing value for Upstream API field %s", field);

                if ("nodeId".equals(field)) {
                    failIfInvalidArgument(value == JsonToken.VALUE_STRING, "Upstream API nodeId must be a string");
                    failIfInvalidArgument(nodeIdValue == null, "Upstream API nodeId must occur once");
                    nodeIdValue = parser.getValueAsString();
                } else {
                    parser.skipChildren();
                }
            }

            failIfInvalidArgument(parser.nextToken() == null,
                    "Upstream API message contains trailing JSON content");
            failIfInvalidArgument(nodeIdValue != null, "Upstream API message has no nodeId");

            return new UpstreamApiMessage(new NodeId(nodeIdValue), payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid Upstream API JSON", ex);
        }
    }

    /**
     * Creates one outbound Engineering API value from already resolved routing context.
     *
     * <p>No second JSON parse is performed on the outbound path. NodeUpstream produced the encoded payload and the
     * application port already knows which node owns it.</p>
     */
    static UpstreamApiMessage outbound(NodeId nodeId, String payload) {
        return new UpstreamApiMessage(nodeId, payload);
    }

    /** Returns the routing identity used by the Engineering adapter for inbound gateway selection. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Returns the complete encoded IF-06 payload unchanged. */
    public String payload() {
        return payload;
    }
}
