package io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.io.IOException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Engineering-API framing around one encoded IF-06 upstream message.
 *
 * <p>Engineering needs NodeId only to select the fixed gateway route. Semantic IF-06 decoding
 * remains owned by NodeUpstream and the encoded payload is preserved unchanged.</p>
 */
public final class UpstreamApiMessage {
    private static final JsonFactory JSON = new JsonFactory();

    private final NodeId nodeId;
    private final String payload;

    /** Construction is restricted to the inbound parser and outbound factory. */
    private UpstreamApiMessage(NodeId nodeId, String payload) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(payload != null, "payload must not be null");

        this.nodeId = nodeId;
        this.payload = payload;
    }

    /**
     * Inbound Engineering framing extracts only the routing identity; all other IF-06 fields
     * remain untouched for the later semantic decode in NodeUpstream.
     */
    public static UpstreamApiMessage parse(String payload) {

        failIfInvalidArgument(payload != null, "payload must not be null");

        try (JsonParser parser = JSON.createParser(payload)) {
            failIfInvalidArgument(
                    parser.nextToken() == JsonToken.START_OBJECT,
                    "Upstream API message must be a JSON object");

            String nodeIdValue = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                failIfInvalidArgument(
                        parser.currentToken() == JsonToken.FIELD_NAME,
                        "Invalid Upstream API JSON object");

                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                failIfInvalidArgument(value != null, "Missing value for Upstream API field %s", field);

                if ("nodeId".equals(field)) {
                    failIfInvalidArgument(
                            value == JsonToken.VALUE_STRING,
                            "Upstream API nodeId must be a string");
                    failIfInvalidArgument(nodeIdValue == null, "Upstream API nodeId must occur once");
                    nodeIdValue = parser.getValueAsString();
                } else {
                    parser.skipChildren();
                }
            }

            failIfInvalidArgument(
                    parser.nextToken() == null,
                    "Upstream API message contains trailing JSON content");
            failIfInvalidArgument(nodeIdValue != null, "Upstream API message has no nodeId");

            return new UpstreamApiMessage(new NodeId(nodeIdValue), payload);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Invalid Upstream API JSON", ex);
        }
    }

    /** Outbound routing context is known already, so the encoded payload is not reparsed. */
    static UpstreamApiMessage createOutboundMessage(NodeId nodeId, String payload) {
        return new UpstreamApiMessage(nodeId, payload);
    }

    /** Routing identity used by the Engineering adapter before semantic decoding. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Complete encoded IF-06 payload; NodeUpstream remains the semantic owner. */
    public String payload() {
        return payload;
    }
}
