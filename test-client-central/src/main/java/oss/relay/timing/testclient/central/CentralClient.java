package oss.relay.timing.testclient.central;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingTimestamp;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javafx.scene.Node;

/**
 * SI-02 Central simulator boundary.
 *
 * <p>This facade owns Central state, protocol actions and Central presentation.
 * Its only external send capability is {@link CentralTransport}; no IF-03 HTTP
 * client is visible inside this module.</p>
 */
public final class CentralClient implements AutoCloseable {
    private final CentralTransport transport;
    private final CentralSystemSimulator simulator;
    private final CentralPane centralPane;
    private final CentralMessagesPane messagesPane;
    private final CentralLogBookPane logBookPane;
    private final Actions actions = new Actions();

    public CentralClient(
            Collection<Integer> configuredLocationIds,
            CentralTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        this.transport = transport;
        simulator = new CentralSystemSimulator(configuredLocationIds);
        centralPane = new CentralPane(simulator, actions);
        messagesPane = new CentralMessagesPane(simulator);
        logBookPane = new CentralLogBookPane(simulator);
    }

    public CentralActions actions() {
        return actions;
    }

    public Node nodesPane() {
        return centralPane.nodesPane();
    }

    public Node locationsPane() {
        return centralPane.locationsPane();
    }

    public Node raceDataPane() {
        return centralPane.raceDataPane();
    }

    public Node timingDataPane() {
        return centralPane.timingDataPane();
    }

    public Node messagesPane() {
        return messagesPane.pane();
    }

    public Node logBookPane() {
        return logBookPane.pane();
    }

    public void connected() {
        simulator.connected();
        refresh();
    }

    public void disconnected() {
        simulator.disconnected();
        refresh();
    }

    public void resetTargetState() {
        simulator.resetTargetState();
        refresh();
    }

    public void accept(
            UpstreamData data,
            Instant eventTime,
            Instant receivedAt,
            String rawPayloadJson) {
        for (UpstreamData reply
                : simulator.accept(
                        data,
                        eventTime,
                        receivedAt,
                        rawPayloadJson)) {
            send(reply);
        }
        refresh();
    }

    public void refresh() {
        centralPane.refresh();
        messagesPane.refresh();
        logBookPane.refresh();
    }

    @Override
    public void close() {
        centralPane.close();
    }

    private long sendPackets(List<UpstreamData> packets) {
        long packetNr = 0L;
        for (UpstreamData packet : packets) {
            packetNr = packet.packetNr();
            send(packet);
        }
        refresh();
        return packetNr;
    }

    private int sendPacketsAndCount(List<UpstreamData> packets) {
        for (UpstreamData packet : packets) {
            send(packet);
        }
        refresh();
        return packets.size();
    }

    private void send(UpstreamData packet) {
        transport.send(packet);
        simulator.recordSent(
                packet,
                Instant.now(),
                CentralUpstreamJson.encode(packet));
        messagesPane.refresh();
    }

    private final class Actions implements CentralControlActions {
        @Override
        public MutationResult setTeamAssignment(
                String registrationId,
                String teamId) {
            int packets =
                    sendPacketsAndCount(
                            simulator.updateTeamAssignments(
                                    List.of(
                                            DefaultUpstreamData.createTeamAssignmentSet(
                                                    registrationId,
                                                    teamId))));
            return new MutationResult(1, packets);
        }

        @Override
        public MutationResult deleteTeamAssignment(
                String registrationId) {
            int packets =
                    sendPacketsAndCount(
                            simulator.updateTeamAssignments(
                                    List.of(
                                            DefaultUpstreamData.createTeamAssignmentDelete(
                                                    registrationId))));
            return new MutationResult(1, packets);
        }

        @Override
        public MutationResult setStageStartRange(
                int stageId,
                String firstRegistrationId,
                String lastRegistrationId,
                String start,
                long intervalMillis) {
            if (intervalMillis < 0L) {
                throw new IllegalArgumentException(
                        "Interval must not be negative");
            }
            List<String> registrations =
                    registrationRange(
                            firstRegistrationId,
                            lastRegistrationId);
            TimingTimestamp first =
                    TimingTimestamp.parse(
                            start == null ? null : start.trim());
            List<UpstreamData.StageStartTimeChange> changes =
                    new ArrayList<>(registrations.size());
            for (int index = 0; index < registrations.size(); index++) {
                changes.add(
                        DefaultUpstreamData.createStageStartSet(
                                registrations.get(index),
                                plusMillis(
                                        first,
                                        Math.multiplyExact(
                                                intervalMillis,
                                                index))));
            }
            int packets =
                    sendPacketsAndCount(
                            simulator.updateStageStarts(
                                    stageId,
                                    changes));
            return new MutationResult(
                    registrations.size(),
                    packets);
        }

        @Override
        public MutationResult deleteStageStartRange(
                int stageId,
                String firstRegistrationId,
                String lastRegistrationId) {
            List<String> registrations =
                    registrationRange(
                            firstRegistrationId,
                            lastRegistrationId);
            List<UpstreamData.StageStartTimeChange> changes =
                    new ArrayList<>(registrations.size());
            for (String registrationId : registrations) {
                changes.add(
                        DefaultUpstreamData.createStageStartDelete(
                                registrationId));
            }
            int packets =
                    sendPacketsAndCount(
                            simulator.updateStageStarts(
                                    stageId,
                                    changes));
            return new MutationResult(
                    registrations.size(),
                    packets);
        }

        @Override
        public long setTeamAssignment(
                String nodeId,
                String registrationId,
                String teamId) {
            return sendPackets(
                    simulator.updateRaceData(
                            new NodeId(nodeId),
                            List.of(
                                    DefaultUpstreamData.createTeamAssignmentSet(
                                            registrationId,
                                            teamId)),
                            List.of()));
        }

        @Override
        public long deleteTeamAssignment(
                String nodeId,
                String registrationId) {
            return sendPackets(
                    simulator.updateRaceData(
                            new NodeId(nodeId),
                            List.of(
                                    DefaultUpstreamData.createTeamAssignmentDelete(
                                            registrationId)),
                            List.of()));
        }

        @Override
        public long setStageStart(
                String nodeId,
                String registrationId,
                String start) {
            TimingTimestamp parsed =
                    TimingTimestamp.parse(
                            start == null ? null : start.trim());
            return sendPackets(
                    simulator.updateRaceData(
                            new NodeId(nodeId),
                            List.of(),
                            List.of(
                                    DefaultUpstreamData.createStageStartSet(
                                            registrationId,
                                            parsed))));
        }

        @Override
        public long deleteStageStart(
                String nodeId,
                String registrationId) {
            return sendPackets(
                    simulator.updateRaceData(
                            new NodeId(nodeId),
                            List.of(),
                            List.of(
                                    DefaultUpstreamData.createStageStartDelete(
                                            registrationId))));
        }

        @Override
        public long requestTimingData(
                String nodeId,
                long fromSequence) {
            return sendPackets(
                    simulator.requestTimingData(
                            new NodeId(nodeId),
                            fromSequence));
        }

        @Override
        public long requestSourceHead(String nodeId) {
            return sendPackets(
                    simulator.requestSourceHead(
                            new NodeId(nodeId)));
        }
    }

    static List<String> registrationRange(
            String firstText,
            String lastText) {
        oss.relay.timing.timingdata.TimingDataTypes.RegistrationId first =
                new oss.relay.timing.timingdata.TimingDataTypes.RegistrationId(
                        firstText == null ? null : firstText.trim());
        oss.relay.timing.timingdata.TimingDataTypes.RegistrationId last =
                new oss.relay.timing.timingdata.TimingDataTypes.RegistrationId(
                        lastText == null ? null : lastText.trim());
        if (first.type() != last.type()) {
            throw new IllegalArgumentException(
                    "Registration range must use one RegistrationId type");
        }
        if (last.number() < first.number()) {
            throw new IllegalArgumentException(
                    "Registration range end must not precede start");
        }

        List<String> result =
                new ArrayList<>(
                        last.number() - first.number() + 1);
        for (int number = first.number();
                number <= last.number();
                number++) {
            result.add(
                    new oss.relay.timing.timingdata.TimingDataTypes.RegistrationId(
                            first.type(),
                            number)
                            .value());
        }
        return List.copyOf(result);
    }

    static TimingTimestamp plusMillis(
            TimingTimestamp value,
            long millis) {
        if (millis < 0L) {
            throw new IllegalArgumentException(
                    "millis must not be negative");
        }
        if (millis == 0L) {
            return value;
        }
        switch (value.knowledge()) {
            case TIME_ONLY:
                return TimingTimestamp.localTime(
                        value.time().plusNanos(
                                Math.multiplyExact(
                                        millis,
                                        1_000_000L)));
            case LOCAL_DATE_TIME:
                LocalDateTime local =
                        LocalDateTime.of(
                                value.date(),
                                value.time())
                                .plusNanos(
                                        Math.multiplyExact(
                                                millis,
                                                1_000_000L));
                return TimingTimestamp.localDateTime(
                        local.toLocalDate(),
                        local.toLocalTime());
            case ABSOLUTE:
                return new TimingTimestamp(
                        value.instant().plusMillis(millis));
            default:
                throw new IllegalStateException(
                        "Unsupported timestamp knowledge "
                                + value.knowledge());
        }
    }
}
