package io.github.brainboxemb.eventtiming.testclient.central;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
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
    private final CentralActions actions = new Actions();

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

    private void send(UpstreamData packet) {
        transport.send(packet);
        simulator.recordSent(
                packet,
                Instant.now(),
                CentralUpstreamJson.encode(packet));
        messagesPane.refresh();
    }

    private final class Actions implements CentralActions {
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
    }
}
