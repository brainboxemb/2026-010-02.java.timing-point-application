package io.github.brainboxemb.eventtiming.testclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central-side IF-06 simulator state.
 *
 * <p>This model is intentionally independent from the direct IF-03/API view. It learns
 * nodes and locations only from semantic upstream traffic, requests missing TimingData
 * after source-head SYNC, answers PING requests and interprets NODE_INFO history.</p>
 */
final class CentralSystemSimulator {
    record SystemView(
            String nodeId,
            boolean connected,
            Instant lastHeartbeat,
            long timingDataHead,
            Integer locationId,
            ApiClient.TimingDataInfo lastRecord) {
    }

    record LocationView(
            int locationId,
            String state,
            String nodeId,
            long sequence,
            Instant changedAt) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<Integer> configuredLocationIds;
    private final Map<String, SystemState> systems = new LinkedHashMap<>();
    private final Map<Integer, LocationState> locations = new LinkedHashMap<>();
    private boolean connected;

    CentralSystemSimulator(Collection<Integer> configuredLocationIds) {
        if (configuredLocationIds == null) {
            throw new IllegalArgumentException("configuredLocationIds must not be null");
        }
        List<Integer> validated = new ArrayList<>();
        for (Integer locationId : configuredLocationIds) {
            if (locationId == null || locationId.intValue() <= 0) {
                throw new IllegalArgumentException("LocationId must be positive");
            }
            if (!validated.contains(locationId)) {
                validated.add(locationId);
            }
        }
        this.configuredLocationIds = List.copyOf(validated);
        restoreConfiguredLocations();
    }

    /**
     * Clears facts learned from the previous target while retaining simulator configuration.
     *
     * <p>A plain disconnect/reconnect intentionally does not call this method: Central then
     * keeps its received source head and can request only the missing suffix. A target change
     * is a different identity boundary and must not inherit NodeId or Location state.</p>
     */
    void resetTargetState() {
        connected = false;
        systems.clear();
        locations.clear();
        restoreConfiguredLocations();
    }

    void connected() {
        connected = true;
    }

    void disconnected() {
        connected = false;
    }

    boolean isConnected() {
        return connected;
    }

    /**
     * Applies one semantic IF-06 JSON value and returns any Central-side replies.
     *
     * <p>The transport owns only the WebSocket event envelope. This model owns the
     * IF-06 meaning and therefore decides when REQ or PING ANS is required.</p>
     */
    List<String> accept(String payloadJson, Instant receivedAt) throws IOException {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson must not be blank");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }

        JsonNode root = JSON.readTree(payloadJson);
        int version = requiredInt(root, "v");
        if (version != 1) {
            throw new IllegalArgumentException("Unsupported IF-06 version: " + version);
        }

        String type = requiredText(root, "type");
        String nodeId = requiredText(root, "nodeId");
        JsonNode data = required(root, "data");
        SystemState system = systems.computeIfAbsent(nodeId, SystemState::new);
        system.lastReceivedAt = receivedAt;

        return switch (type) {
            case "PING" -> handlePing(system, data, receivedAt);
            case "SYNC" -> handleSync(system, data);
            case "TIMING_DATA" -> {
                handleTimingData(system, data, receivedAt);
                yield Collections.emptyList();
            }
            default -> Collections.emptyList();
        };
    }

    List<SystemView> systems() {
        List<SystemView> result = new ArrayList<>(systems.size());
        for (SystemState state : systems.values()) {
            result.add(new SystemView(
                    state.nodeId,
                    connected,
                    state.lastHeartbeat,
                    state.timingDataHead,
                    state.locationId,
                    state.lastRecord));
        }
        return List.copyOf(result);
    }

    List<LocationView> locations() {
        List<LocationView> result = new ArrayList<>(locations.size());
        for (LocationState state : locations.values()) {
            result.add(new LocationView(
                    state.locationId,
                    state.state,
                    state.nodeId,
                    state.sequence,
                    state.changedAt));
        }
        return List.copyOf(result);
    }

    private List<String> handlePing(
            SystemState system,
            JsonNode data,
            Instant receivedAt) {
        String operation = requiredText(data, "op");
        String mode = optionalText(data, "mode");
        String pingId = optionalText(data, "pingId");
        if ("REQ".equals(operation)) {
            system.lastHeartbeat = receivedAt;
            return List.of(pingAnswer(system.nodeId, mode, pingId));
        }
        return Collections.emptyList();
    }

    private List<String> handleSync(SystemState system, JsonNode data) {
        String stream = requiredText(data, "stream");
        long remoteHead = requiredLong(data, "seq");
        if (!"TIMING_DATA".equals(stream)) {
            return Collections.emptyList();
        }
        if (remoteHead > system.timingDataHead) {
            return List.of(timingDataRequest(system.nodeId, system.timingDataHead + 1L));
        }
        return Collections.emptyList();
    }

    private void handleTimingData(
            SystemState system,
            JsonNode data,
            Instant receivedAt) {
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalArgumentException("TIMING_DATA data must be a non-empty array");
        }

        for (JsonNode recordJson : data) {
            ApiClient.TimingDataInfo record = ApiClient.parseTimingData(recordJson);
            if (!system.nodeId.equals(record.timingNodeId())) {
                throw new IllegalArgumentException(
                        "TIMING_DATA record nodeId does not match outer nodeId");
            }

            long sequence = record.sequenceNumber();
            if (sequence <= system.timingDataHead) {
                continue;
            }
            if (sequence != system.timingDataHead + 1L) {
                throw new IllegalArgumentException(
                        "TIMING_DATA sequence gap for "
                                + system.nodeId
                                + ": expected "
                                + (system.timingDataHead + 1L)
                                + " but got "
                                + sequence);
            }

            system.timingDataHead = sequence;
            system.lastRecord = record;
            applyLifecycle(system, record, receivedAt);
        }
    }

    private void applyLifecycle(
            SystemState system,
            ApiClient.TimingDataInfo record,
            Instant receivedAt) {
        if (!"NODE_INFO".equals(record.recordType())) {
            return;
        }

        if (record.codes().contains("OPEN")) {
            system.locationId = record.locationId();
            LocationState location = locations.computeIfAbsent(
                    record.locationId(),
                    LocationState::new);
            location.state = "OPEN";
            location.nodeId = system.nodeId;
            location.sequence = record.sequenceNumber();
            location.changedAt = receivedAt;
        } else if (record.codes().contains("CLOSE")) {
            if (system.locationId != null
                    && system.locationId.intValue() == record.locationId()) {
                system.locationId = null;
            }
            LocationState location = locations.computeIfAbsent(
                    record.locationId(),
                    LocationState::new);
            location.state = "CLOSED";
            location.nodeId = system.nodeId;
            location.sequence = record.sequenceNumber();
            location.changedAt = receivedAt;
        }
    }

    private void restoreConfiguredLocations() {
        for (Integer locationId : configuredLocationIds) {
            locations.put(
                    locationId,
                    new LocationState(locationId.intValue()));
        }
    }

    private static String timingDataRequest(String nodeId, long fromSequence) {
        return "{"
                + "\"v\":1,"
                + "\"type\":\"REQ\","
                + "\"nodeId\":" + quote(nodeId) + ","
                + "\"data\":{"
                + "\"stream\":\"TIMING_DATA\","
                + "\"fromSeq\":" + fromSequence
                + "}}";
    }

    private static String pingAnswer(String nodeId, String mode, String pingId) {
        StringBuilder json = new StringBuilder();
        json.append('{')
                .append("\"v\":1,")
                .append("\"type\":\"PING\",")
                .append("\"nodeId\":").append(quote(nodeId)).append(',')
                .append("\"data\":{\"op\":\"ANS\"");
        if (mode != null) {
            json.append(",\"mode\":").append(quote(mode));
        }
        if (pingId != null) {
            json.append(",\"pingId\":").append(quote(pingId));
        }
        return json.append("}}").toString();
    }

    private static String quote(String value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to encode IF-06 text", ex);
        }
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing IF-06 field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("IF-06 field is not text: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("IF-06 field is not text: " + field);
        }
        return value.asText();
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("IF-06 field is not an integer: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("IF-06 field is not an integer: " + field);
        }
        return value.longValue();
    }

    private static final class SystemState {
        private final String nodeId;
        private Instant lastReceivedAt;
        private Instant lastHeartbeat;
        private long timingDataHead;
        private Integer locationId;
        private ApiClient.TimingDataInfo lastRecord;

        private SystemState(String nodeId) {
            this.nodeId = nodeId;
        }
    }

    private static final class LocationState {
        private final int locationId;
        private String state = "UNKNOWN";
        private String nodeId;
        private long sequence;
        private Instant changedAt;

        private LocationState(int locationId) {
            this.locationId = locationId;
        }
    }
}
