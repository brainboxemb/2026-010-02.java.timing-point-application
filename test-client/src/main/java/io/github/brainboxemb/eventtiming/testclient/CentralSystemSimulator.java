package io.github.brainboxemb.eventtiming.testclient;

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
 * <p>This service is intentionally independent from the direct IF-03/API view. It learns
 * nodes and locations only from typed UpstreamData received on the upstream-role session,
 * requests missing TimingData after source-head SYNC, answers PING requests and interprets
 * NODE_OPEN/NODE_CLOSE history.</p>
 */
final class CentralSystemSimulator {
    record SystemView(
            String nodeId,
            boolean connected,
            Instant lastHeartbeat,
            long timingDataHead,
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

    private List<UpstreamData> handleSync(
            SystemState system,
            UpstreamData.Sync sync) {
        if (sync.stream() != UpstreamData.Stream.TIMING_DATA) {
            return Collections.emptyList();
        }

        long remoteHead = sync.sequence();
        if (remoteHead > system.timingDataHead) {
            return List.of(
                    DefaultUpstreamData.req(
                            sync.timingNodeId(),
                            UpstreamData.Stream.TIMING_DATA,
                            system.timingDataHead + 1L));
        }
        return Collections.emptyList();
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
        private Instant lastHeartbeat;
        private long timingDataHead;
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
