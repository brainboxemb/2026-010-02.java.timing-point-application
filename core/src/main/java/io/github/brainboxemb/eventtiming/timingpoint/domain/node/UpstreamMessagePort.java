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
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * TimingNode-owned semantic upstream handler.
 *
 * <p>All mutable upstream state is handled on the owning TimingNode serial lane.
 * The port decides semantic protocol outcomes; transport connectors only move
 * UpstreamData messages.</p>
 */
public final class UpstreamMessagePort {
    private static final int REPLAY_BATCH_SIZE = 256;

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
     * Handles one inbound node-scoped upstream message.
     *
     * <p>This is the semantic entry point used by the application router. The
     * router resolves only the target node; this port owns message meaning and
     * any ACK/REQ/PING response produced by that node.</p>
     */
    public List<UpstreamData> handle(
            UpstreamData.NodeScoped data) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null");
        }
        if (!timingNode.timingNodeId().equals(
                data.timingNodeId())) {
            throw new IllegalArgumentException(
                    "UpstreamData NodeId does not match TimingNode");
        }

        switch (data.family()) {
            case RACE_DATA:
                if (!(data instanceof UpstreamData.RaceData)) {
                    throw new IllegalArgumentException(
                            "RACE_DATA has incompatible semantic type");
                }
                return handleRaceData(
                        (UpstreamData.RaceData) data);
            case SYNC:
                if (!(data instanceof UpstreamData.Sync)) {
                    throw new IllegalArgumentException(
                            "SYNC has incompatible semantic type");
                }
                return handleSync(
                        (UpstreamData.Sync) data);
            case REQ:
                if (!(data instanceof UpstreamData.Req)) {
                    throw new IllegalArgumentException(
                            "REQ has incompatible semantic type");
                }
                return handleReq(
                        (UpstreamData.Req) data);
            case PING:
                if (!(data instanceof UpstreamData.Ping)) {
                    throw new IllegalArgumentException(
                            "PING has incompatible semantic type");
                }
                UpstreamData.Ping response =
                        handlePing(
                                (UpstreamData.Ping) data);
                return response == null
                        ? Collections.<UpstreamData>emptyList()
                        : Collections.<UpstreamData>singletonList(response);
            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by TimingNode: "
                                + data.family());
        }
    }

    private List<UpstreamData> handleRaceData(
            UpstreamData.RaceData raceData) {
        RaceDataApplyResult apply =
                applyRaceData(
                        raceData);

        switch (apply.outcome()) {
            case APPLIED:
            case DUPLICATE:
                return Collections.<UpstreamData>singletonList(
                        DefaultUpstreamData.ack(
                                raceData.timingNodeId(),
                                UpstreamData.Stream.RACE_DATA,
                                apply.acknowledgeSequence()));
            case GAP:
                return Collections.<UpstreamData>singletonList(
                        DefaultUpstreamData.req(
                                raceData.timingNodeId(),
                                UpstreamData.Stream.RACE_DATA,
                                apply.requestedFromSequence()));
            case REJECTED:
                throw new IllegalArgumentException(
                        apply.message());
            default:
                throw new IllegalStateException(
                        "Unsupported RaceData outcome "
                                + apply.outcome());
        }
    }

    /**
     * Creates this node's current TIMING_DATA source-head advertisement.
     *
     * <p>An empty LogBook has source head 0. The caller decides when the
     * advertisement is published; the node port owns the source-head meaning.</p>
     */
    public UpstreamData.Sync timingDataSync() {
        return DefaultUpstreamData.sync(
                timingNode.timingNodeId(),
                UpstreamData.Stream.TIMING_DATA,
                timingDataHead());
    }

    /** Returns the current committed TIMING_DATA source head; zero means empty. */
    public long timingDataHead() {
        return timingNode.query(
                TimingNodeQueries.timingDataHeadSequence());
    }

    /**
     * Returns at most {@code limit} retained TimingData records from the
     * inclusive source sequence.
     *
     * <p>Each call is one bounded ordered read on the existing TimingNode lane.
     * Replay loops therefore do not monopolize the lane for an entire request.</p>
     */
    public List<TimingData> timingDataFrom(
            long fromSequence,
            int limit) {
        if (fromSequence < 1L) {
            throw new IllegalArgumentException(
                    "fromSequence must be >= 1");
        }
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "limit must be >= 1");
        }

        List<TimingData> records =
                new ArrayList<TimingData>(
                        limit);
        timingNode.query(
                TimingNodeQueries.visitTimingDataRange(
                        fromSequence,
                        limit,
                        records::add));
        return Collections.unmodifiableList(
                records);
    }

    private List<UpstreamData> handleSync(
            UpstreamData.Sync sync) {
        if (sync.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException(
                    "Inbound SYNC supports RACE_DATA only");
        }

        long applied =
                raceDataAppliedSequence();
        long sourceHead =
                sync.sequence();

        if (sourceHead == applied) {
            return Collections.emptyList();
        }
        if (sourceHead > applied) {
            return Collections.<UpstreamData>singletonList(
                    DefaultUpstreamData.req(
                            timingNode.timingNodeId(),
                            UpstreamData.Stream.RACE_DATA,
                            applied + 1L));
        }

        throw new IllegalStateException(
                "Central RACE_DATA source head "
                        + sourceHead
                        + " is behind already applied sequence "
                        + applied);
    }

    private List<UpstreamData> handleReq(
            UpstreamData.Req req) {
        if (req.stream() != UpstreamData.Stream.TIMING_DATA) {
            throw new UnsupportedOperationException(
                    "Inbound REQ supports TIMING_DATA only");
        }

        /*
         * Capture the source head once for this request. New commits may happen
         * between bounded replay batches, but they belong to a later REQ.
         */
        long capturedHead =
                timingDataHead();
        if (req.fromSequence() > capturedHead) {
            return Collections.emptyList();
        }

        List<UpstreamData> responses =
                new ArrayList<UpstreamData>();
        long nextSequence =
                req.fromSequence();

        while (nextSequence <= capturedHead) {
            int limit =
                    (int) Math.min(
                            (long) REPLAY_BATCH_SIZE,
                            capturedHead - nextSequence + 1L);
            List<TimingData> records =
                    timingDataFrom(
                            nextSequence,
                            limit);

            if (records.isEmpty()) {
                throw new IllegalStateException(
                        "LogBook head "
                                + capturedHead
                                + " advertised record "
                                + nextSequence
                                + " but replay returned no data");
            }

            TimingData first =
                    records.get(0);
            if (first.sequenceNumber() != nextSequence) {
                throw new IllegalStateException(
                        "TimingData replay sequence gap: expected "
                                + nextSequence
                                + " but got "
                                + first.sequenceNumber());
            }

            TimingData last =
                    records.get(
                            records.size() - 1);
            if (last.sequenceNumber() > capturedHead) {
                throw new IllegalStateException(
                        "TimingData replay crossed captured head "
                                + capturedHead);
            }

            responses.add(
                    DefaultUpstreamData.timingData(
                            timingNode.timingNodeId(),
                            records));
            nextSequence =
                    last.sequenceNumber() + 1L;
        }

        return Collections.unmodifiableList(
                responses);
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

        return DefaultUpstreamData.ping(
                timingNode.timingNodeId(),
                UpstreamData.PingOperation.REQ,
                UpstreamData.PingMode.HB,
                heartbeatPingId);
    }

    private UpstreamData.Ping handlePingOnNodeLane(
            UpstreamData.Ping ping) {
        if (ping.operation()
                == UpstreamData.PingOperation.REQ) {
            return DefaultUpstreamData.ping(
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
