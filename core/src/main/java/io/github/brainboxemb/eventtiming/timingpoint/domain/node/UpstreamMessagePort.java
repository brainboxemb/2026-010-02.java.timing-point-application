package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.UniqueTeamRegistrations;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TimingNode-owned semantic upstream port.
 *
 * <p>The port is bound to one node-scoped {@link UpstreamStream}. Real transport
 * I/O stays outside the TimingNode. The stream only exposes local non-blocking
 * inbound/outbound queues; semantic processing runs as short tasks on the
 * existing TimingNode serial lane.</p>
 */
public final class UpstreamMessagePort {
    private final TimingNode timingNode;
    private final UpstreamStream stream;
    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;
    private final UniqueTeamRegistrations uniqueTeamRegistrations;
    private final AtomicBoolean inputTaskScheduled =
            new AtomicBoolean();

    private long lastAppliedRaceDataSequence;

    /*
     * Heartbeat scheduling is supplied by runtime composition, while heartbeat
     * state changes remain serialized with other node work.
     */
    private long heartbeatRequestsSent;
    private long heartbeatAnswersReceived;
    private long heartbeatMissedIntervals;
    private boolean heartbeatAnswerPending;
    private String heartbeatPingId;

    UpstreamMessagePort(
            TimingNode timingNode,
            UpstreamStream stream,
            EventData eventData,
            TeamAssignments teamAssignments,
            StageStartTimes stageStartTimes,
            UniqueTeamRegistrations uniqueTeamRegistrations) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        if (stream == null) {
            throw new IllegalArgumentException(
                    "stream must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (teamAssignments == null) {
            throw new IllegalArgumentException(
                    "teamAssignments must not be null");
        }
        if (stageStartTimes == null) {
            throw new IllegalArgumentException(
                    "stageStartTimes must not be null");
        }
        if (uniqueTeamRegistrations == null) {
            throw new IllegalArgumentException(
                    "uniqueTeamRegistrations must not be null");
        }
        this.timingNode = timingNode;
        this.stream = stream;
        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
        this.uniqueTeamRegistrations = uniqueTeamRegistrations;
    }

    /**
     * Signals that the node-scoped stream may contain inbound data.
     *
     * <p>The caller performs no semantic work and never waits for the TimingNode
     * lane. At most one upstream task is pending/running for this port.</p>
     *
     * @return true when work is already scheduled or was admitted now
     */
    public boolean inputAvailable() {
        if (!stream.isAvailable()
                || !stream.hasMessage()) {
            return false;
        }
        if (!inputTaskScheduled.compareAndSet(
                false,
                true)) {
            return true;
        }

        if (timingNode.offerNodeTask(
                new UpstreamMessageTask(this))) {
            return true;
        }

        inputTaskScheduled.set(false);
        return false;
    }

    /**
     * Processes one stream item while the TimingNode serial lane is current.
     *
     * <p>One message per task is intentional. If more data is already queued,
     * another task is appended to the same lane so registrations and other node
     * work are not starved by an upstream burst.</p>
     */
    void processNextOnNodeLane() {
        try {
            if (!stream.isAvailable()
                    || !stream.hasMessage()) {
                return;
            }

            UpstreamData data =
                    stream.read();
            processOnNodeLane(data);
        } finally {
            scheduleNextTurnOrRelease();
        }
    }

    private void processOnNodeLane(
            UpstreamData data) {
        if (!(data instanceof UpstreamData.NodeScoped)) {
            throw new IllegalArgumentException(
                    "UpstreamData is not node-scoped: "
                            + data.family());
        }

        UpstreamData.NodeScoped nodeData =
                (UpstreamData.NodeScoped) data;
        if (!timingNode.timingNodeId().equals(
                nodeData.timingNodeId())) {
            throw new IllegalArgumentException(
                    "UpstreamData NodeId does not match node-scoped stream");
        }

        switch (data.family()) {
            case RACE_DATA:
                if (!(data instanceof UpstreamData.RaceData)) {
                    throw new IllegalArgumentException(
                            "RACE_DATA has incompatible semantic type");
                }
                processRaceDataOnNodeLane(
                        (UpstreamData.RaceData) data);
                return;

            case PING:
                if (!(data instanceof UpstreamData.Ping)) {
                    throw new IllegalArgumentException(
                            "PING has incompatible semantic type");
                }
                processPingOnNodeLane(
                        (UpstreamData.Ping) data);
                return;

            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by TimingNode: "
                                + data.family());
        }
    }

    /**
     * Applies one ordered RACE_DATA message and writes the protocol response
     * directly to the same node-scoped stream.
     */
    private void processRaceDataOnNodeLane(
            UpstreamData.RaceData raceData) {
        long receivedSequence =
                raceData.sequence();
        if (receivedSequence < 1L
                || receivedSequence > TimingData.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "RACE_DATA sequence is outside the supported range");
        }

        if (receivedSequence <= lastAppliedRaceDataSequence) {
            sendRaceDataAck(
                    lastAppliedRaceDataSequence);
            return;
        }

        long expectedSequence =
                lastAppliedRaceDataSequence + 1L;
        if (receivedSequence != expectedSequence) {
            stream.send(
                    DefaultUpstreamData.req(
                            timingNode.timingNodeId(),
                            UpstreamData.Stream.RACE_DATA,
                            expectedSequence));
            return;
        }

        /*
         * Apply one RACE_DATA message transactionally. Work is done on copies;
         * live node data changes only after every change has validated.
         */
        TeamAssignments updatedAssignments =
                teamAssignments.copy();
        StageStartTimes updatedStartTimes =
                stageStartTimes.copy();

        applyTeamAssignmentChanges(
                updatedAssignments,
                raceData);
        applyStageStartChanges(
                updatedStartTimes,
                raceData);
        updatedAssignments.validate();

        teamAssignments.replaceWith(
                updatedAssignments);
        stageStartTimes.replaceWith(
                updatedStartTimes);
        lastAppliedRaceDataSequence =
                receivedSequence;

        sendRaceDataAck(
                receivedSequence);
    }

    private void sendRaceDataAck(
            long sequence) {
        stream.send(
                DefaultUpstreamData.ack(
                        timingNode.timingNodeId(),
                        UpstreamData.Stream.RACE_DATA,
                        sequence));
    }

    /**
     * One scheduled heartbeat step for this TimingNode.
     *
     * <p>The request is written to the same stream as all other outbound
     * upstream traffic. No transport I/O occurs on the node lane.</p>
     */
    public void heartbeatTick() {
        onNodeLane(
                () -> {
                    heartbeatTickOnNodeLane();
                    return null;
                },
                "upstreamHeartbeat");
    }

    public HeartbeatStatus heartbeatStatus() {
        return onNodeLane(
                this::heartbeatStatusOnNodeLane,
                "upstreamHeartbeatStatus");
    }

    private void heartbeatTickOnNodeLane() {
        if (!stream.isAvailable()) {
            return;
        }

        if (heartbeatAnswerPending) {
            heartbeatMissedIntervals++;
        }

        heartbeatRequestsSent++;
        heartbeatAnswerPending = true;
        heartbeatPingId =
                "hb-" + heartbeatRequestsSent;

        stream.send(
                DefaultUpstreamData.ping(
                        timingNode.timingNodeId(),
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        heartbeatPingId));
    }

    private void processPingOnNodeLane(
            UpstreamData.Ping ping) {
        if (ping.operation()
                == UpstreamData.PingOperation.REQ) {
            stream.send(
                    DefaultUpstreamData.ping(
                            timingNode.timingNodeId(),
                            UpstreamData.PingOperation.ANS,
                            ping.mode(),
                            ping.pingId()));
            return;
        }

        if (ping.operation()
                == UpstreamData.PingOperation.ANS
                && ping.mode() == UpstreamData.PingMode.HB
                && heartbeatAnswerPending
                && heartbeatPingId != null
                && heartbeatPingId.equals(
                        ping.pingId())) {
            heartbeatAnswerPending = false;
            heartbeatAnswersReceived++;
        }
    }

    public long raceDataAppliedSequence() {
        return onNodeLane(
                () -> Long.valueOf(
                        lastAppliedRaceDataSequence),
                "raceDataAppliedSequence")
                .longValue();
    }

    public TeamId teamIdFor(
            RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        return onNodeLane(
                () -> resolveTeamId(
                        registrationId),
                "upstreamTeamIdFor");
    }

    TimingTimestamp stageStartTimeFor(
            TeamId teamId) {
        return onNodeLane(
                () -> stageStartTimes.startFor(
                        teamId),
                "upstreamStageStartTimeFor");
    }

    public int uniqueTeamRegistrationCount() {
        return onNodeLane(
                () -> Integer.valueOf(
                        uniqueTeamRegistrations.uniqueTeamCount(
                                this::resolveTeamId)),
                "uniqueTeamRegistrationCount")
                .intValue();
    }

    private void applyTeamAssignmentChanges(
            TeamAssignments updatedAssignments,
            UpstreamData.RaceData raceData) {
        if (raceData.teamAssignmentChanges() == null) {
            throw new IllegalArgumentException(
                    "team assignment changes must not be null");
        }

        for (UpstreamData.TeamAssignmentChange change
                : raceData.teamAssignmentChanges()) {
            if (change == null) {
                throw new IllegalArgumentException(
                        "team assignment change must not be null");
            }

            switch (change.operation()) {
                case SET:
                    if (eventData.teamIdFor(
                            change.registrationId()) != null) {
                        throw new IllegalArgumentException(
                                "active assignment cannot override fixed EventData TeamId for "
                                        + change.registrationId());
                    }
                    updatedAssignments.assign(
                            change.registrationId(),
                            change.teamId());
                    break;

                case DEL:
                    updatedAssignments.remove(
                            change.registrationId());
                    break;

                default:
                    throw new IllegalArgumentException(
                            "unsupported team assignment operation "
                                    + change.operation());
            }
        }
    }

    private static void applyStageStartChanges(
            StageStartTimes updatedStartTimes,
            UpstreamData.RaceData raceData) {
        if (raceData.stageStartTimeChanges() == null) {
            throw new IllegalArgumentException(
                    "stage start changes must not be null");
        }

        for (UpstreamData.StageStartTimeChange change
                : raceData.stageStartTimeChanges()) {
            if (change == null) {
                throw new IllegalArgumentException(
                        "stage start change must not be null");
            }

            switch (change.operation()) {
                case SET:
                    updatedStartTimes.set(
                            change.teamId(),
                            change.start());
                    break;

                case DEL:
                    updatedStartTimes.remove(
                            change.teamId());
                    break;

                default:
                    throw new IllegalArgumentException(
                            "unsupported stage start operation "
                                    + change.operation());
            }
        }
    }

    private TeamId resolveTeamId(
            RegistrationId registrationId) {
        TeamId fixed =
                eventData.teamIdFor(
                        registrationId);
        return fixed != null
                ? fixed
                : teamAssignments.teamIdFor(
                        registrationId);
    }

    /**
     * Keeps one upstream task pending while more input is already queued.
     */
    private void scheduleNextTurnOrRelease() {
        if (stream.isAvailable()
                && stream.hasMessage()
                && timingNode.offerNodeTask(
                        new UpstreamMessageTask(this))) {
            return;
        }

        inputTaskScheduled.set(false);
    }

    private <R> R onNodeLane(
            Callable<R> task,
            String operation) {
        return timingNode.runNodeTask(
                task,
                operation);
    }

    public static final class HeartbeatStatus {
        private final long requestsSent;
        private final long answersReceived;
        private final long missedIntervals;
        private final boolean answerPending;

        private HeartbeatStatus(
                long requestsSent,
                long answersReceived,
                long missedIntervals,
                boolean answerPending) {
            this.requestsSent = requestsSent;
            this.answersReceived = answersReceived;
            this.missedIntervals = missedIntervals;
            this.answerPending = answerPending;
        }

        public long requestsSent() {
            return requestsSent;
        }

        public long answersReceived() {
            return answersReceived;
        }

        public long missedIntervals() {
            return missedIntervals;
        }

        public boolean answerPending() {
            return answerPending;
        }
    }
}
