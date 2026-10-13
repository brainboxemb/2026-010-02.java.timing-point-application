package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RaceDataApplyResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.UniqueTeamRegistrations;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.Callable;

/**
 * TimingNode-owned semantic upstream handler.
 *
 * <p>All mutable upstream state is handled on the owning TimingNode serial lane.
 * The port decides semantic protocol outcomes; transport connectors only move
 * UpstreamData messages.</p>
 */
public final class UpstreamMessagePort {
    private final TimingNode timingNode;
    private final EventData eventData;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;
    private final UniqueTeamRegistrations uniqueTeamRegistrations;

    private long lastAppliedRaceDataSequence;

    /*
     * Heartbeat scheduling is supplied by runtime composition, but the state
     * transition itself belongs to this node and therefore lives on the node
     * serial lane.
     */
    private long heartbeatRequestsSent;
    private long heartbeatAnswersReceived;
    private long heartbeatMissedIntervals;
    private boolean heartbeatAnswerPending;
    private String heartbeatPingId;

    UpstreamMessagePort(
            TimingNode timingNode,
            EventData eventData,
            TeamAssignments teamAssignments,
            StageStartTimes stageStartTimes,
            UniqueTeamRegistrations uniqueTeamRegistrations) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
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
        this.eventData = eventData;
        this.teamAssignments = teamAssignments;
        this.stageStartTimes = stageStartTimes;
        this.uniqueTeamRegistrations = uniqueTeamRegistrations;
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

    /**
     * One scheduled heartbeat step for this TimingNode.
     *
     * <p>The scheduler may be shared by many nodes. Calling this method always
     * serializes the heartbeat decision with this node's registration, LogBook
     * and upstream state. If the previous heartbeat is still unanswered, one
     * missed interval is recorded before the next request is created.</p>
     */
    public UpstreamData.Ping heartbeatTick() {
        return onNodeLane(
                this::heartbeatTickOnNodeLane,
                "upstreamHeartbeat");
    }

    /**
     * Handles one inbound node-scoped PING.
     *
     * <p>REQ is answered with ANS using the same mode/pingId. An HB answer that
     * matches the currently outstanding heartbeat marks that heartbeat as
     * answered. Answers never trigger another answer.</p>
     */
    public UpstreamData.Ping handlePing(
            UpstreamData.Ping ping) {
        if (ping == null) {
            throw new IllegalArgumentException(
                    "ping must not be null");
        }
        /*
         * Target validation does not depend on mutable node state. Reject a
         * misrouted message before entering the serial lane so callers receive
         * the actual contract error instead of a wrapped node-task failure.
         */
        if (!timingNode.timingNodeId().equals(
                ping.timingNodeId())) {
            throw new IllegalArgumentException(
                    "PING NodeId does not match TimingNode");
        }
        return onNodeLane(
                () -> handlePingOnNodeLane(ping),
                "upstreamPing");
    }

    public HeartbeatStatus heartbeatStatus() {
        return onNodeLane(
                this::heartbeatStatusOnNodeLane,
                "upstreamHeartbeatStatus");
    }

    private UpstreamData.Ping heartbeatTickOnNodeLane() {
        if (heartbeatAnswerPending) {
            heartbeatMissedIntervals++;
        }

        heartbeatRequestsSent++;
        heartbeatAnswerPending = true;
        heartbeatPingId =
                "hb-" + heartbeatRequestsSent;

        return io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData.ping(
                timingNode.timingNodeId(),
                UpstreamData.PingOperation.REQ,
                UpstreamData.PingMode.HB,
                heartbeatPingId);
    }

    private UpstreamData.Ping handlePingOnNodeLane(
            UpstreamData.Ping ping) {
        if (ping.operation()
                == UpstreamData.PingOperation.REQ) {
            return io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData.ping(
                    timingNode.timingNodeId(),
                    UpstreamData.PingOperation.ANS,
                    ping.mode(),
                    ping.pingId());
        }

        if (ping.operation()
                == UpstreamData.PingOperation.ANS
                && ping.mode() == UpstreamData.PingMode.HB
                && heartbeatAnswerPending
                && heartbeatPingId != null
                && heartbeatPingId.equals(ping.pingId())) {
            heartbeatAnswerPending = false;
            heartbeatAnswersReceived++;
        }
        return null;
    }

    private HeartbeatStatus heartbeatStatusOnNodeLane() {
        return new HeartbeatStatus(
                heartbeatRequestsSent,
                heartbeatAnswersReceived,
                heartbeatMissedIntervals,
                heartbeatAnswerPending);
    }

    public RaceDataApplyResult applyRaceData(
            UpstreamData.RaceData raceData) {
        if (raceData == null) {
            throw new IllegalArgumentException(
                    "raceData must not be null");
        }
        return onNodeLane(
                () -> applyRaceDataOnNodeLane(raceData),
                "upstreamRaceData");
    }

    public long raceDataAppliedSequence() {
        return onNodeLane(
                () -> Long.valueOf(lastAppliedRaceDataSequence),
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
                () -> resolveTeamId(registrationId),
                "upstreamTeamIdFor");
    }

    TimingTimestamp stageStartTimeFor(
            TeamId teamId) {
        return onNodeLane(
                () -> stageStartTimes.startFor(teamId),
                "upstreamStageStartTimeFor");
    }

    /**
     * Returns the current number of unique teams with one or more active
     * registrations.
     *
     * <p>Resolution happens at read time so a later TeamAssignment immediately
     * changes the count without rewriting committed TimingData.</p>
     */
    public int uniqueTeamRegistrationCount() {
        return onNodeLane(
                () -> Integer.valueOf(
                        uniqueTeamRegistrations.uniqueTeamCount(
                                this::resolveTeamId)),
                "uniqueTeamRegistrationCount")
                .intValue();
    }

    private RaceDataApplyResult applyRaceDataOnNodeLane(
            UpstreamData.RaceData raceData) {
        long receivedSequence = raceData.sequence();
        if (receivedSequence < 1L
                || receivedSequence > TimingData.MAX_SEQUENCE_NUMBER) {
            return RaceDataApplyResult.rejected(
                    receivedSequence,
                    lastAppliedRaceDataSequence,
                    "RACE_DATA sequence is outside the supported range");
        }

        if (!timingNode.timingNodeId().equals(
                raceData.timingNodeId())) {
            return RaceDataApplyResult.rejected(
                    receivedSequence,
                    lastAppliedRaceDataSequence,
                    "RACE_DATA NodeId does not match TimingNode");
        }

        if (receivedSequence <= lastAppliedRaceDataSequence) {
            return RaceDataApplyResult.duplicate(
                    receivedSequence,
                    lastAppliedRaceDataSequence);
        }

        long expectedSequence =
                lastAppliedRaceDataSequence + 1L;
        if (receivedSequence != expectedSequence) {
            return RaceDataApplyResult.gap(
                    receivedSequence,
                    lastAppliedRaceDataSequence,
                    expectedSequence);
        }

        /*
         * Apply one RACE_DATA message transactionally. Work is done on copies;
         * live node data is replaced only after every change has validated.
         */
        TeamAssignments updatedAssignments =
                teamAssignments.copy();
        StageStartTimes updatedStartTimes =
                stageStartTimes.copy();

        try {
            applyTeamAssignmentChanges(
                    updatedAssignments,
                    raceData);
            applyStageStartChanges(
                    updatedStartTimes,
                    raceData);
            updatedAssignments.validate();
        } catch (IllegalArgumentException ex) {
            return RaceDataApplyResult.rejected(
                    receivedSequence,
                    lastAppliedRaceDataSequence,
                    ex.getMessage());
        }

        teamAssignments.replaceWith(
                updatedAssignments);
        stageStartTimes.replaceWith(
                updatedStartTimes);
        lastAppliedRaceDataSequence =
                receivedSequence;

        return RaceDataApplyResult.applied(
                receivedSequence);
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

    private <R> R onNodeLane(
            Callable<R> task,
            String operation) {
        return timingNode.runNodeTask(
                task,
                operation);
    }
}
