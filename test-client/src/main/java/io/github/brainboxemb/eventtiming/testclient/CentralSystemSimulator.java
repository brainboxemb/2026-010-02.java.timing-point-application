package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

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
    record SystemView(
            String nodeId,
            boolean connected,
            Instant lastHeartbeat,
            long timingDataHead,
            long raceDataHead,
            long raceDataAcknowledged,
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
     * keeps both TIMING_DATA receive-head and RACE_DATA source/replay state. A target change
     * is a different identity boundary and must not inherit either protocol stream.</p>
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
     * Creates and retains the next ordered Central-to-node RACE_DATA value.
     *
     * <p>The caller owns transport delivery. Retaining the immutable value here lets a later
     * RACE_DATA REQ replay exactly the original semantic change set.</p>
     */
    UpstreamData.RaceData createRaceData(
            NodeId nodeId,
            List<UpstreamData.TeamAssignmentChange> teamAssignmentChanges,
            List<UpstreamData.StageStartTimeChange> stageStartTimeChanges) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }
        if (teamAssignmentChanges == null || stageStartTimeChanges == null) {
            throw new IllegalArgumentException("RACE_DATA change lists must not be null");
        }

        SystemState system =
                systems.computeIfAbsent(
                        nodeId.value(),
                        ignored -> new SystemState(nodeId.value()));

        long sequence = system.raceDataHead + 1L;
        UpstreamData.RaceData raceData =
                DefaultUpstreamData.raceData(
                        nodeId,
                        sequence,
                        teamAssignmentChanges,
                        stageStartTimeChanges);

        system.raceDataHead = sequence;
        system.raceDataHistory.put(Long.valueOf(sequence), raceData);
        return raceData;
    }

    /** Applies one semantic IF-06 value and returns any Central-side protocol replies. */
    List<UpstreamData> accept(
            UpstreamData data,
            Instant receivedAt) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt must not be null");
        }

        String nodeId = data.timingNodeId().value();
        SystemState system = systems.computeIfAbsent(nodeId, SystemState::new);

        return switch (data.family()) {
            case PING -> handlePing(system, (UpstreamData.Ping) data, receivedAt);
            case SYNC -> handleSync(system, (UpstreamData.Sync) data);
            case REQ -> handleReq(system, (UpstreamData.Req) data);
            case ACK -> {
                handleAck(system, (UpstreamData.Ack) data);
                yield Collections.emptyList();
            }
            case TIMING_DATA -> {
                handleTimingData(
                        system,
                        (UpstreamData.TimingDataTransfer) data,
                        receivedAt);
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
                    state.raceDataHead,
                    state.raceDataAcknowledged,
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
            SystemState system,
            UpstreamData.Ping ping,
            Instant receivedAt) {
        if (ping.operation() == UpstreamData.PingOperation.REQ) {
            system.lastHeartbeat = receivedAt;
            return List.of(
                    DefaultUpstreamData.ping(
                            ping.timingNodeId(),
                            UpstreamData.PingOperation.ANS,
                            ping.mode(),
                            ping.pingId()));
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
            SystemState system,
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
                        system.raceDataHead));

        long remoteHead = sync.sequence();
        if (remoteHead > system.timingDataHead) {
            replies.add(
                    DefaultUpstreamData.req(
                            sync.timingNodeId(),
                            UpstreamData.Stream.TIMING_DATA,
                            system.timingDataHead + 1L));
        } else if (remoteHead < system.timingDataHead) {
            throw new IllegalStateException(
                    "SI-01 TIMING_DATA source head "
                            + remoteHead
                            + " is behind Central receive head "
                            + system.timingDataHead);
        }
        return List.copyOf(replies);
    }

    /** Replays retained Central-owned RACE_DATA from the requested inclusive sequence. */
    private List<UpstreamData> handleReq(
            SystemState system,
            UpstreamData.Req request) {
        if (request.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException(
                    "Central inbound REQ supports RACE_DATA only");
        }

        long fromSequence = request.fromSequence();
        if (fromSequence > system.raceDataHead) {
            return Collections.emptyList();
        }

        List<UpstreamData> replay = new ArrayList<>();
        for (long sequence = fromSequence;
                sequence <= system.raceDataHead;
                sequence++) {
            UpstreamData.RaceData retained =
                    system.raceDataHistory.get(Long.valueOf(sequence));
            if (retained == null) {
                throw new IllegalStateException(
                        "Missing retained RACE_DATA sequence "
                                + sequence
                                + " for "
                                + system.nodeId);
            }
            replay.add(retained);
        }
        return List.copyOf(replay);
    }

    /** Applies one cumulative RACE_DATA acknowledgement from SI-01. */
    private void handleAck(
            SystemState system,
            UpstreamData.Ack acknowledgement) {
        if (acknowledgement.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException(
                    "Central inbound ACK supports RACE_DATA only");
        }

        long sequence = acknowledgement.sequence();
        if (sequence > system.raceDataHead) {
            throw new IllegalStateException(
                    "RACE_DATA ACK "
                            + sequence
                            + " exceeds Central source head "
                            + system.raceDataHead);
        }
        if (sequence > system.raceDataAcknowledged) {
            system.raceDataAcknowledged = sequence;
        }
    }

    private void handleTimingData(
            SystemState system,
            UpstreamData.TimingDataTransfer transfer,
            Instant receivedAt) {
        for (TimingRecord record : transfer.records()) {
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
            TimingRecord record,
            Instant receivedAt) {
        if (record.kind() == TimingRecord.Kind.NODE_OPEN) {
            system.locationId = Integer.valueOf(record.locationId());
            LocationState location = locations.computeIfAbsent(
                    record.locationId(),
                    LocationState::new);
            location.state = "OPEN";
            location.nodeId = system.nodeId;
            location.sequence = record.sequenceNumber();
            location.changedAt = receivedAt;
        } else if (record.kind() == TimingRecord.Kind.NODE_CLOSE) {
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

    private static final class SystemState {
        private final String nodeId;
        private final Map<Long, UpstreamData.RaceData> raceDataHistory =
                new LinkedHashMap<>();
        private Instant lastHeartbeat;
        private long timingDataHead;
        private long raceDataHead;
        private long raceDataAcknowledged;
        private Integer locationId;
        private TimingRecord lastRecord;

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
