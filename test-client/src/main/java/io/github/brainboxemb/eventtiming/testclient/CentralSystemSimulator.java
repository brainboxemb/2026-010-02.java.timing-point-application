package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Central-side IF-06 simulator state.
 *
 * <p>TIMING_DATA receive progress is durable protocol state for this simulator session.
 * Central-owned RACE_DATA is current state rather than a packet history: per node the
 * simulator keeps the authoritative state, the last confirmed baseline and at most one
 * immutable normal DN_PACKET outstanding.</p>
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

        MessageView(
                long ordinal,
                MessageDirection direction,
                Instant messageTime,
                Instant receivedAt,
                UpstreamData data) {
            this(
                    ordinal,
                    direction,
                    messageTime,
                    receivedAt,
                    data,
                    ApiEventClient.encodeUpstreamData(data));
        }
    }

    record NodeView(
            String nodeId,
            boolean connected,
            Instant lastHeartbeat,
            long timingDataHead,
            long lastDnPacketAcknowledged,
            Long outstandingDnPacketNr,
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
    private final List<TimingRecord> timingRecords = new ArrayList<>();

    private boolean connected;
    private long nextMessageOrdinal = 1L;

    CentralSystemSimulator(Collection<Integer> configuredLocationIds) {
        this(configuredLocationIds, Clock.systemUTC());
    }

    CentralSystemSimulator(
            Collection<Integer> configuredLocationIds,
            Clock clock) {
        if (configuredLocationIds == null) {
            throw new IllegalArgumentException(
                    "configuredLocationIds must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }

        List<Integer> validated = new ArrayList<>();
        for (Integer locationId : configuredLocationIds) {
            if (locationId == null || locationId.intValue() <= 0) {
                throw new IllegalArgumentException(
                        "LocationId must be positive");
            }
            if (!validated.contains(locationId)) {
                validated.add(locationId);
            }
        }

        this.configuredLocationIds = List.copyOf(validated);
        this.clock = clock;
        restoreConfiguredLocations();
    }

    /** Clears facts learned from the previous target while retaining simulator configuration. */
    void resetTargetState() {
        connected = false;
        nodes.clear();
        locations.clear();
        messages.clear();
        timingRecords.clear();
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
     * Applies one Central-owned RaceData state update and returns any packet that became sendable.
     *
     * <p>If another normal DN_PACKET is outstanding the authoritative state changes now, but that
     * packet remains immutable. The resulting RaceData delta is built only after the outstanding
     * packet is confirmed.</p>
     */
    List<UpstreamData> updateRaceData(
            NodeId nodeId,
            List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges,
            List<UpstreamData.StageStartTimeChange> stageStartTimeChanges) {
        requireConnected();
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (teamAssignmentChanges == null || stageStartTimeChanges == null) {
            throw new IllegalArgumentException(
                    "RACE_DATA change lists must not be null");
        }

        NodeState node =
                nodes.computeIfAbsent(
                        nodeId.value(),
                        ignored -> new NodeState(nodeId.value()));

        if (node.locationId == null || !"OPEN".equals(node.lifecycleState)) {
            throw new IllegalStateException(
                    "RACE_DATA requires an OPEN LocationId for "
                            + nodeId.value());
        }

        applyCurrentRaceData(
                node,
                teamAssignmentChanges,
                stageStartTimeChanges);
        return sendNextNormalPacketIfPossible(node);
    }

    /** Returns the exact outstanding normal downstream packet for an explicit retry. */
    UpstreamData outstandingPacket(NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        NodeState node = nodes.get(nodeId.value());
        return node == null ? null : node.outstanding;
    }

    /** Applies one received SI-01 packet using one timestamp for test callers. */
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
     * Applies one received SI-01 packet and returns newly sendable Central packets.
     *
     * <p>PING answers bypass the normal downstream stop-and-wait lane. All other Central
     * semantic output is queued and becomes one positive-ID DN_PACKET at a time.</p>
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
            throw new IllegalArgumentException(
                    "rawPayloadJson must not be blank");
        }
        if (data.direction() != UpstreamData.Direction.UP_PACKET) {
            throw new IllegalArgumentException(
                    "Central accepts UP_PACKET only");
        }

        recordMessage(
                MessageDirection.IN,
                data,
                messageTime(data, eventTime),
                receivedAt,
                rawPayloadJson);

        String nodeId = data.timingNodeId().value();
        NodeState node =
                nodes.computeIfAbsent(
                        nodeId,
                        NodeState::new);

        switch (data.family()) {
            case PING:
                return handlePing(
                        node,
                        data,
                        (UpstreamData.Ping) data.message(),
                        receivedAt);

            case RECV:
                return handleReceive(
                        node,
                        (UpstreamData.Receive) data.message());

            case SEQ:
                return handleSequence(
                        node,
                        (UpstreamData.Sequence) data.message());

            case TIMING_DATA:
                handleTimingData(
                        node,
                        (UpstreamData.TimingDataTransfer) data.message(),
                        receivedAt);
                return Collections.emptyList();

            default:
                throw new UnsupportedOperationException(
                        "Central inbound family is not implemented: "
                                + data.family());
        }
    }

    /** Records one Central-to-node packet after transport send has been requested. */
    void recordSent(
            UpstreamData data,
            Instant sentAt) {
        recordSent(
                data,
                sentAt,
                ApiEventClient.encodeUpstreamData(data));
    }

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
            throw new IllegalArgumentException(
                    "rawPayloadJson must not be blank");
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

    /** Returns the complete reconstructed Central LogBook in accepted receive order. */
    List<TimingRecord> timingRecords() {
        return List.copyOf(timingRecords);
    }

    List<NodeView> nodes() {
        List<NodeView> result = new ArrayList<>(nodes.size());
        for (NodeState state : nodes.values()) {
            result.add(
                    new NodeView(
                            state.nodeId,
                            connected,
                            state.lastHeartbeat,
                            state.timingDataHead,
                            state.lastDnPacketAcknowledged,
                            state.outstanding == null
                                    ? null
                                    : Long.valueOf(
                                            state.outstanding.packetNr()),
                            state.lifecycleState,
                            state.locationId,
                            state.lastRecord));
        }
        return List.copyOf(result);
    }

    List<LocationView> locations() {
        List<LocationView> result = new ArrayList<>(locations.size());
        for (LocationState state : locations.values()) {
            result.add(
                    new LocationView(
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
            UpstreamData packet,
            UpstreamData.Ping ping,
            Instant receivedAt) {
        if (ping.operation() != UpstreamData.PingOperation.REQ) {
            return Collections.emptyList();
        }

        node.lastHeartbeat = receivedAt;
        return List.of(
                DefaultUpstreamData.createDnPacket(
                        packet.timingNodeId(),
                        0L,
                        DefaultUpstreamData.createPing(
                                UpstreamData.PingOperation.ANS,
                                ping.mode(),
                                ping.pingId(),
                                new TimingTimestamp(clock.instant()))));
    }

    private List<UpstreamData> handleSequence(
            NodeState node,
            UpstreamData.Sequence sequence) {
        if (sequence.operation() == UpstreamData.SequenceOperation.REQ) {
            node.pendingMessages.addLast(
                    DefaultUpstreamData.createSequenceAnswer(
                            node.timingDataHead));
            return outstandingOrNext(node);
        }

        long sourceHead = sequence.sequence().longValue();
        if (sourceHead < node.timingDataHead) {
            throw new IllegalStateException(
                    "SI-01 TIMING_DATA source head "
                            + sourceHead
                            + " is behind Central receive head "
                            + node.timingDataHead);
        }

        /*
         * TIMING_DATA_REQ remains the replay-to-live handoff barrier even when Central is
         * already caught up. SI-01 captures the LogBook head when processing this request.
         */
        if (node.timingDataHead < TimingRecord.MAX_SEQUENCE_NUMBER) {
            node.pendingMessages.addLast(
                    DefaultUpstreamData.createTimingDataRequest(
                            node.timingDataHead + 1L));
        }
        return outstandingOrNext(node);
    }

    /** Retries the exact outstanding packet, or starts the next queued normal packet. */
    private List<UpstreamData> outstandingOrNext(NodeState node) {
        if (node.outstanding != null) {
            return List.of(node.outstanding);
        }
        return sendNextNormalPacketIfPossible(node);
    }

    private List<UpstreamData> handleReceive(
            NodeState node,
            UpstreamData.Receive receive) {
        long receivedPacketNr = receive.receivedPacketNr();

        if (receivedPacketNr == 0L) {
            resetDownstreamState(node);
            return sendNextNormalPacketIfPossible(node);
        }

        if (node.outstanding == null) {
            if (receivedPacketNr == node.lastDnPacketAcknowledged) {
                return Collections.emptyList();
            }
            throw new IllegalStateException(
                    "RECV "
                            + receivedPacketNr
                            + " has no matching outstanding DN_PACKET for "
                            + node.nodeId);
        }

        long outstandingId = node.outstanding.packetNr();
        if (receivedPacketNr < outstandingId) {
            return List.of(node.outstanding);
        }
        if (receivedPacketNr > outstandingId) {
            throw new IllegalStateException(
                    "RECV "
                            + receivedPacketNr
                            + " exceeds outstanding DN_PACKET "
                            + outstandingId);
        }

        applyAcknowledgedPacket(
                node,
                node.outstanding);
        node.lastDnPacketAcknowledged = receivedPacketNr;
        node.outstanding = null;
        return sendNextNormalPacketIfPossible(node);
    }

    /**
     * Resets the assumed downstream baseline after a fresh-node RECV 0.
     *
     * <p>The current SI-01 sends RECV 0 only before it has processed a normal downstream
     * packet in this runtime. Central therefore rebuilds from an empty RaceData baseline
     * and starts the downstream packet sequence again at 1.</p>
     */
    private void resetDownstreamState(NodeState node) {
        node.outstanding = null;
        node.pendingMessages.clear();
        node.lastDnPacketAcknowledged = 0L;
        node.nextDnPacketNr = 1L;
        node.acknowledgedTeams.clear();
        node.acknowledgedStageStarts.clear();
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
            timingRecords.add(record);
            applyLifecycle(
                    node,
                    record,
                    receivedAt);
        }
    }

    private List<UpstreamData> sendNextNormalPacketIfPossible(
            NodeState node) {
        if (!connected || node.outstanding != null) {
            return Collections.emptyList();
        }

        UpstreamData.Message message = node.pendingMessages.pollFirst();
        if (message == null) {
            message = createNextRaceDataDelta(node);
        }
        if (message == null) {
            return Collections.emptyList();
        }

        UpstreamData packet =
                DefaultUpstreamData.createDnPacket(
                        new NodeId(node.nodeId),
                        nextDnPacketNr(node),
                        message);
        node.outstanding = packet;
        return List.of(packet);
    }

    /** Builds at most one bounded delta from the last confirmed RaceData baseline. */
    private UpstreamData.RaceData createNextRaceDataDelta(
            NodeState node) {
        if (node.locationId == null
                || !"OPEN".equals(node.lifecycleState)) {
            return null;
        }

        List<UpstreamData.TeamAssignmentChange> teamChanges =
                new ArrayList<>();
        List<UpstreamData.StageStartTimeChange> startChanges =
                new ArrayList<>();

        for (Map.Entry<RegistrationId, TeamId> entry
                : node.acknowledgedTeams.entrySet()) {
            if (!node.currentTeams.containsKey(entry.getKey())) {
                teamChanges.add(
                        new UpstreamData.TeamAssignmentChange(
                                UpstreamData.ChangeOperation.DEL,
                                entry.getKey(),
                                null));
                if (changeCount(teamChanges, startChanges)
                        >= UpstreamData.MAX_RACE_DATA_CHANGES) {
                    return createRaceData(
                            node,
                            teamChanges,
                            startChanges);
                }
            }
        }

        for (Map.Entry<RegistrationId, TeamId> entry
                : node.currentTeams.entrySet()) {
            if (!Objects.equals(
                    entry.getValue(),
                    node.acknowledgedTeams.get(entry.getKey()))) {
                teamChanges.add(
                        new UpstreamData.TeamAssignmentChange(
                                UpstreamData.ChangeOperation.SET,
                                entry.getKey(),
                                entry.getValue()));
                if (changeCount(teamChanges, startChanges)
                        >= UpstreamData.MAX_RACE_DATA_CHANGES) {
                    return createRaceData(
                            node,
                            teamChanges,
                            startChanges);
                }
            }
        }

        for (Map.Entry<RegistrationId, TimingTimestamp> entry
                : node.acknowledgedStageStarts.entrySet()) {
            if (!node.currentStageStarts.containsKey(entry.getKey())) {
                startChanges.add(
                        new UpstreamData.StageStartTimeChange(
                                UpstreamData.ChangeOperation.DEL,
                                entry.getKey(),
                                null));
                if (changeCount(teamChanges, startChanges)
                        >= UpstreamData.MAX_RACE_DATA_CHANGES) {
                    return createRaceData(
                            node,
                            teamChanges,
                            startChanges);
                }
            }
        }

        for (Map.Entry<RegistrationId, TimingTimestamp> entry
                : node.currentStageStarts.entrySet()) {
            if (!Objects.equals(
                    entry.getValue(),
                    node.acknowledgedStageStarts.get(entry.getKey()))) {
                startChanges.add(
                        new UpstreamData.StageStartTimeChange(
                                UpstreamData.ChangeOperation.SET,
                                entry.getKey(),
                                entry.getValue()));
                if (changeCount(teamChanges, startChanges)
                        >= UpstreamData.MAX_RACE_DATA_CHANGES) {
                    return createRaceData(
                            node,
                            teamChanges,
                            startChanges);
                }
            }
        }

        if (teamChanges.isEmpty() && startChanges.isEmpty()) {
            return null;
        }
        return createRaceData(
                node,
                teamChanges,
                startChanges);
    }

    private static UpstreamData.RaceData createRaceData(
            NodeState node,
            List<UpstreamData.TeamAssignmentChange> teamChanges,
            List<UpstreamData.StageStartTimeChange> startChanges) {
        return DefaultUpstreamData.createRaceData(
                new LocationId(node.locationId.intValue()),
                teamChanges,
                startChanges);
    }

    private static int changeCount(
            List<UpstreamData.TeamAssignmentChange> teamChanges,
            List<UpstreamData.StageStartTimeChange> startChanges) {
        return teamChanges.size() + startChanges.size();
    }

    private static void applyCurrentRaceData(
            NodeState node,
            List<UpstreamData.TeamAssignmentChange> teamChanges,
            List<UpstreamData.StageStartTimeChange> startChanges) {
        for (UpstreamData.TeamAssignmentChange change : teamChanges) {
            if (change == null) {
                throw new IllegalArgumentException(
                        "team assignment change must not be null");
            }
            switch (change.operation()) {
                case SET:
                    node.currentTeams.put(
                            change.registrationId(),
                            change.teamId());
                    break;
                case DEL:
                    node.currentTeams.remove(change.registrationId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported team assignment operation "
                                    + change.operation());
            }
        }

        for (UpstreamData.StageStartTimeChange change : startChanges) {
            if (change == null) {
                throw new IllegalArgumentException(
                        "stage-start change must not be null");
            }
            switch (change.operation()) {
                case SET:
                    node.currentStageStarts.put(
                            change.registrationId(),
                            change.start());
                    break;
                case DEL:
                    node.currentStageStarts.remove(
                            change.registrationId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unsupported stage-start operation "
                                    + change.operation());
            }
        }
    }

    private static void applyAcknowledgedPacket(
            NodeState node,
            UpstreamData packet) {
        if (packet.family() != UpstreamData.Family.RACE_DATA) {
            return;
        }

        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) packet.message();
        for (UpstreamData.TeamAssignmentChange change
                : raceData.teamAssignmentChanges()) {
            if (change.operation() == UpstreamData.ChangeOperation.SET) {
                node.acknowledgedTeams.put(
                        change.registrationId(),
                        change.teamId());
            } else {
                node.acknowledgedTeams.remove(
                        change.registrationId());
            }
        }

        for (UpstreamData.StageStartTimeChange change
                : raceData.stageStartTimeChanges()) {
            if (change.operation() == UpstreamData.ChangeOperation.SET) {
                node.acknowledgedStageStarts.put(
                        change.registrationId(),
                        change.start());
            } else {
                node.acknowledgedStageStarts.remove(
                        change.registrationId());
            }
        }
    }

    private static long nextDnPacketNr(NodeState node) {
        if (node.nextDnPacketNr <= 0L) {
            throw new IllegalStateException(
                    "DN_PACKET id space exhausted for "
                            + node.nodeId);
        }

        long result = node.nextDnPacketNr;
        node.nextDnPacketNr =
                result == Long.MAX_VALUE
                        ? 0L
                        : result + 1L;
        return result;
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

            LocationState location =
                    locations.computeIfAbsent(
                            record.locationId(),
                            LocationState::new);
            location.state = "OPEN";
            location.nodeId = node.nodeId;
            location.sequence = record.sequenceNumber();
            location.changedAt = receivedAt;
        } else if (record.kind() == TimingRecord.Kind.NODE_CLOSE) {
            node.lifecycleState = "CLOSED";
            if (node.locationId != null
                    && node.locationId.intValue()
                            == record.locationId()) {
                node.locationId = null;
            }

            LocationState location =
                    locations.computeIfAbsent(
                            record.locationId(),
                            LocationState::new);
            location.state = "CLOSED";
            location.nodeId = node.nodeId;
            location.sequence = record.sequenceNumber();
            location.changedAt = receivedAt;
        }
    }

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

        LocationState previous =
                locations.computeIfAbsent(
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
                    (UpstreamData.Ping) data.message();
            if (ping.time() != null) {
                return ping.time().instant();
            }
        }
        return fallback;
    }

    private void restoreConfiguredLocations() {
        for (Integer locationId : configuredLocationIds) {
            locations.put(
                    locationId,
                    new LocationState(locationId.intValue()));
        }
    }

    private void requireConnected() {
        if (!connected) {
            throw new IllegalStateException(
                    "Central upstream is not connected");
        }
    }

    private static final class NodeState {
        private final String nodeId;

        private final Map<RegistrationId, TeamId> currentTeams =
                new LinkedHashMap<>();
        private final Map<RegistrationId, TimingTimestamp> currentStageStarts =
                new LinkedHashMap<>();
        private final Map<RegistrationId, TeamId> acknowledgedTeams =
                new LinkedHashMap<>();
        private final Map<RegistrationId, TimingTimestamp> acknowledgedStageStarts =
                new LinkedHashMap<>();
        private final Deque<UpstreamData.Message> pendingMessages =
                new ArrayDeque<>();

        private Instant lastHeartbeat;
        private long timingDataHead;
        private long nextDnPacketNr = 1L;
        private long lastDnPacketAcknowledged;
        private UpstreamData outstanding;
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
