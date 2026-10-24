package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Clock;
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
 * <p>This service is intentionally independent from the direct IF-03/API view. It owns
 * Central-side source/replay state while {@link ApiEventClient} owns only transport and codec
 * adaptation. TIMING_DATA is consumed from SI-01; RACE_DATA is an independent Central-owned
 * ordered source per NodeId.</p>
 */
final class CentralSystemSimulator {
    enum MessageDirection {
        IN,
        OUT
    }

    record MessageView(
            long ordinal,
            MessageDirection direction,
            Instant messageTime,
            Instant receivedAt,
            UpstreamData data,
            String rawPayloadJson) {
    }

    record NodeView(
            String nodeId,
            boolean connected,
            Instant lastHeartbeat,
            long timingDataHead,
            long raceDataHead,
            long raceDataAcknowledged,
            String lifecycleState,
            Integer locationId,
            TimingRecord lastRecord) {
    }

    record LocationView(
            int locationId,
            String state,
            String nodeId,
            long sequence,
            Instant changedAt) {
    }

    private static final int MAX_MESSAGE_HISTORY = 2000;

    private final List<Integer> configuredLocationIds;
    private final Clock clock;
    private final Map<String, NodeState> nodes = new LinkedHashMap<>();
    private final Map<Integer, LocationState> locations = new LinkedHashMap<>();
    private final List<MessageView> messages = new ArrayList<>();
    private boolean connected;
    private long nextMessageOrdinal = 1L;

    CentralSystemSimulator(Collection<Integer> configuredLocationIds) {
        this(configuredLocationIds, Clock.systemUTC());
    }

    CentralSystemSimulator(Collection<Integer> configuredLocationIds, Clock clock) {
        if (configuredLocationIds == null) {
            throw new IllegalArgumentException("configuredLocationIds must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
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
        this.clock = clock;
        restoreConfiguredLocations();
    }

    /**
     * Clears facts learned from the previous target while retaining simulator configuration.
     *
     * <p>A plain disconnect/reconnect intentionally does not call this method: Central then
     * keeps both TIMING_DATA receive-head and RACE_DATA source/replay state. A target change
     * is a different identity boundary and must not inherit either protocol stream.</p>
     */
    void resetTargetState() {
        connected = false;
        nodes.clear();
        locations.clear();
        messages.clear();
        nextMessageOrdinal = 1L;
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
     * Creates and retains the next ordered Central-to-node RACE_DATA value.
     *
     * <p>The caller owns transport delivery. Retaining the immutable value here lets a later
     * RACE_DATA REQ replay exactly the original semantic change set.</p>
     */
    UpstreamData.RaceData createRaceData(
            NodeId nodeId,
            List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges,
            List<UpstreamData.StageStartTimeChange> stageStartTimeChanges) {
        if (!connected) {
            throw new IllegalStateException("Central upstream is not connected");
        }
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (teamAssignmentChanges == null || stageStartTimeChanges == null) {
            throw new IllegalArgumentException("RACE_DATA change lists must not be null");
        }

        NodeState node =
                nodes.computeIfAbsent(
                        nodeId.value(),
                        ignored -> new NodeState(nodeId.value()));

        if (node.locationId == null || !"OPEN".equals(node.lifecycleState)) {
            throw new IllegalStateException(
                    "RACE_DATA requires an OPEN LocationId for " + nodeId.value());
        }

        LocationId locationId = new LocationId(node.locationId.intValue());
        long sequence = node.raceDataHead + 1L;
        UpstreamData.RaceData raceData =
                DefaultUpstreamData.raceData(
                        nodeId,
                        locationId,
                        sequence,
                        teamAssignmentChanges,
                        stageStartTimeChanges);

        node.raceDataHead = sequence;
        node.raceDataHistory.put(Long.valueOf(sequence), raceData);
        return raceData;
    }

    /**
     * Applies one semantic IF-06 value using one timestamp for legacy/test callers.
     *
     * <p>Production reception supplies the distinct IF-03 event time and local receive time
     * through the three-argument overload.</p>
     */
    List<UpstreamData> accept(
            UpstreamData data,
            Instant receivedAt) {
        return accept(
                data,
                receivedAt,
                receivedAt,
                ApiEventClient.encodeUpstreamData(data));
    }

    /**
     * Applies one semantic IF-06 value and retains source/message time separately from receipt time.
     *
     * <p>PING carries its own semantic message time. Other families use the IF-03 event time as
     * their message-time fallback because IF-06 defines no common message timestamp for them.</p>
     */
    List<UpstreamData> accept(
            UpstreamData data,
            Instant eventTime,
            Instant receivedAt,
            String rawPayloadJson) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (eventTime == null) {
            throw new IllegalArgumentException("eventTime must not be null");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }
        if (rawPayloadJson == null || rawPayloadJson.isBlank()) {
            throw new IllegalArgumentException("rawPayloadJson must not be blank");
        }

        recordMessage(
                MessageDirection.IN,
                data,
                messageTime(data, eventTime),
                receivedAt,
                rawPayloadJson);

        String nodeId = data.timingNodeId().value();
        NodeState node = nodes.computeIfAbsent(nodeId, NodeState::new);

        return switch (data.family()) {
            case PING -> handlePing(node, (UpstreamData.Ping) data, receivedAt);
            case SYNC -> handleSync(node, (UpstreamData.Sync) data);
            case REQ -> handleReq(node, (UpstreamData.Req) data);
            case ACK -> {
                handleAck(node, (UpstreamData.Ack) data);
                yield Collections.emptyList();
            }
            case TIMING_DATA -> {
                handleTimingData(
                        node,
                        (UpstreamData.TimingDataTransfer) data,
                        receivedAt);
                yield Collections.emptyList();
            }
            default -> Collections.emptyList();
        };
    }

    /**
     * Records one semantic Central-to-node message after transport send has been requested.
     *
     * <p>The bounded message journal is inspection state only. RACE_DATA replay uses the
     * independent retained source history and is therefore not affected when old journal
     * rows are evicted.</p>
     */
    void recordSent(
            UpstreamData data,
            Instant sentAt,
            String rawPayloadJson) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (sentAt == null) {
            throw new IllegalArgumentException("sentAt must not be null");
        }
        if (rawPayloadJson == null || rawPayloadJson.isBlank()) {
            throw new IllegalArgumentException("rawPayloadJson must not be blank");
        }
        recordMessage(
                MessageDirection.OUT,
                data,
                messageTime(data, sentAt),
                null,
                rawPayloadJson);
    }

    List<MessageView> messages() {
        return List.copyOf(messages);
    }

    private void recordMessage(
            MessageDirection direction,
            UpstreamData data,
            Instant messageTime,
            Instant receivedAt,
            String rawPayloadJson) {
        messages.add(
                new MessageView(
                        nextMessageOrdinal++,
                        direction,
                        messageTime,
                        receivedAt,
                        data,
                        rawPayloadJson));
        if (messages.size() > MAX_MESSAGE_HISTORY) {
            messages.remove(0);
        }
    }

    /** Resolves the best source/message timestamp without inventing protocol semantics. */
    private static Instant messageTime(
            UpstreamData data,
            Instant fallback) {
        if (data.family() == UpstreamData.Family.PING) {
            UpstreamData.Ping ping =
                    (UpstreamData.Ping) data;
            if (ping.time() != null) {
                return ping.time().instant();
            }
        }
        return fallback;
    }

    List<NodeView> nodes() {
        List<NodeView> result = new ArrayList<>(nodes.size());
        for (NodeState state : nodes.values()) {
            result.add(new NodeView(
                    state.nodeId,
                    connected,
                    state.lastHeartbeat,
                    state.timingDataHead,
                    state.raceDataHead,
                    state.raceDataAcknowledged,
                    state.lifecycleState,
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

    private List<UpstreamData> handlePing(
            NodeState node,
            UpstreamData.Ping ping,
            Instant receivedAt) {
        if (ping.operation() == UpstreamData.PingOperation.REQ) {
            node.lastHeartbeat = receivedAt;
            return List.of(
                    DefaultUpstreamData.ping(
                            ping.timingNodeId(),
                            UpstreamData.PingOperation.ANS,
                            ping.mode(),
                            ping.pingId(),
                            new TimingTimestamp(clock.instant())));
        }
        return Collections.emptyList();
    }

    /**
     * Reconciles SI-01's TIMING_DATA head and advertises Central's RACE_DATA head.
     *
     * <p>SI-01 starts a node upstream session by advertising TIMING_DATA. Central answers
     * with its own independent source head and requests any missing TimingData suffix.</p>
     */
    private List<UpstreamData> handleSync(
            NodeState node,
            UpstreamData.Sync sync) {
        if (sync.stream() != UpstreamData.Stream.TIMING_DATA) {
            throw new UnsupportedOperationException(
                    "Central inbound SYNC supports TIMING_DATA only");
        }

        List<UpstreamData> replies = new ArrayList<>(2);
        replies.add(
                DefaultUpstreamData.sync(
                        sync.timingNodeId(),
                        UpstreamData.Stream.RACE_DATA,
                        node.raceDataHead));

        long remoteHead = sync.sequence();
        if (remoteHead < node.timingDataHead) {
            throw new IllegalStateException(
                    "SI-01 TIMING_DATA source head "
                            + remoteHead
                            + " is behind Central receive head "
                            + node.timingDataHead);
        }

        /*
         * REQ is also the replay-to-live handoff barrier when Central is already caught up.
         * SI-01 captures its newer current LogBook head while handling this request, so records
         * committed between SYNC and REQ join replay instead of arriving early as live data.
         */
        if (node.timingDataHead < TimingRecord.MAX_SEQUENCE_NUMBER) {
            replies.add(
                    DefaultUpstreamData.req(
                            sync.timingNodeId(),
                            UpstreamData.Stream.TIMING_DATA,
                            node.timingDataHead + 1L));
        }
        return List.copyOf(replies);
    }

    /** Replays retained Central-owned RACE_DATA from the requested inclusive sequence. */
    private List<UpstreamData> handleReq(
            NodeState node,
            UpstreamData.Req request) {
        if (request.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException(
                    "Central inbound REQ supports RACE_DATA only");
        }

        long fromSequence = request.fromSequence();
        if (fromSequence > node.raceDataHead) {
            return Collections.emptyList();
        }

        List<UpstreamData> replay = new ArrayList<>();
        for (long sequence = fromSequence;
                sequence <= node.raceDataHead;
                sequence++) {
            UpstreamData.RaceData retained =
                    node.raceDataHistory.get(Long.valueOf(sequence));
            if (retained == null) {
                throw new IllegalStateException(
                        "Missing retained RACE_DATA sequence "
                                + sequence
                                + " for "
                                + node.nodeId);
            }
            replay.add(retained);
        }
        return List.copyOf(replay);
    }

    /** Applies one cumulative RACE_DATA acknowledgement from SI-01. */
    private void handleAck(
            NodeState node,
            UpstreamData.Ack acknowledgement) {
        if (acknowledgement.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException(
                    "Central inbound ACK supports RACE_DATA only");
        }

        long sequence = acknowledgement.sequence();
        if (sequence > node.raceDataHead) {
            throw new IllegalStateException(
                    "RACE_DATA ACK "
                            + sequence
                            + " exceeds Central source head "
                            + node.raceDataHead);
        }
        if (sequence > node.raceDataAcknowledged) {
            node.raceDataAcknowledged = sequence;
        }
    }

    private void handleTimingData(
            NodeState node,
            UpstreamData.TimingDataTransfer transfer,
            Instant receivedAt) {
        for (TimingRecord record : transfer.records()) {
            long sequence = record.sequenceNumber();
            if (sequence <= node.timingDataHead) {
                continue;
            }
            if (sequence != node.timingDataHead + 1L) {
                throw new IllegalArgumentException(
                        "TIMING_DATA sequence gap for "
                                + node.nodeId
                                + ": expected "
                                + (node.timingDataHead + 1L)
                                + " but got "
                                + sequence);
            }

            node.timingDataHead = sequence;
            node.lastRecord = record;
            applyLifecycle(node, record, receivedAt);
        }
    }

    private void applyLifecycle(
            NodeState node,
            TimingRecord record,
            Instant receivedAt) {
        if (record.kind() == TimingRecord.Kind.NODE_OPEN) {
            closePreviousLocationOnMove(
                    node,
                    record.locationId(),
                    record.sequenceNumber(),
                    receivedAt);

            node.lifecycleState = "OPEN";
            node.locationId = Integer.valueOf(record.locationId());
            LocationState location = locations.computeIfAbsent(
                    record.locationId(),
                    LocationState::new);
            location.state = "OPEN";
            location.nodeId = node.nodeId;
            location.sequence = record.sequenceNumber();
            location.changedAt = receivedAt;
        } else if (record.kind() == TimingRecord.Kind.NODE_CLOSE) {
            node.lifecycleState = "CLOSED";
            if (node.locationId != null
                    && node.locationId.intValue() == record.locationId()) {
                node.locationId = null;
            }
            LocationState location = locations.computeIfAbsent(
                    record.locationId(),
                    LocationState::new);
            location.state = "CLOSED";
            location.nodeId = node.nodeId;
            location.sequence = record.sequenceNumber();
            location.changedAt = receivedAt;
        }
    }

    /**
     * A TimingNode can only represent one active location at a time.
     *
     * <p>Central therefore treats a later NODE_OPEN for another LocationId as an
     * implicit close of the previously active location, even when no explicit
     * NODE_CLOSE record was emitted between both OPEN records.</p>
     */
    private void closePreviousLocationOnMove(
            NodeState node,
            int nextLocationId,
            long sequence,
            Instant changedAt) {
        Integer previousLocationId = node.locationId;
        if (previousLocationId == null
                || previousLocationId.intValue() == nextLocationId) {
            return;
        }

        LocationState previous = locations.computeIfAbsent(
                previousLocationId.intValue(),
                LocationState::new);
        if (previous.nodeId != null
                && !previous.nodeId.equals(node.nodeId)) {
            return;
        }

        previous.state = "CLOSED";
        previous.nodeId = node.nodeId;
        previous.sequence = sequence;
        previous.changedAt = changedAt;
    }

    private void restoreConfiguredLocations() {
        for (Integer locationId : configuredLocationIds) {
            locations.put(
                    locationId,
                    new LocationState(locationId.intValue()));
        }
    }

    private static final class NodeState {
        private final String nodeId;
        private final Map<Long, UpstreamData.RaceData> raceDataHistory =
                new LinkedHashMap<>();
        private Instant lastHeartbeat;
        private long timingDataHead;
        private long raceDataHead;
        private long raceDataAcknowledged;
        private String lifecycleState = "UNKNOWN";
        private Integer locationId;
        private TimingRecord lastRecord;

        private NodeState(String nodeId) {
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
