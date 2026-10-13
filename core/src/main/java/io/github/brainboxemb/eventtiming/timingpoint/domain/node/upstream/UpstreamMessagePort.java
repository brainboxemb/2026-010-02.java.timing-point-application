package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.UniqueTeamRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * TimingNode-owned semantic upstream port.
 *
 * <p>The port is bound to one node-scoped {@link UpstreamStream}. Real transport
 * I/O stays outside the TimingNode. The stream only exposes local non-blocking
 * inbound/outbound queues; semantic processing runs as short tasks on the
 * existing TimingNode serial lane.</p>
 */
public final class UpstreamMessagePort {
    private static final int REPLAY_BATCH_SIZE = 256;

    private final NodeId nodeId;
    private final NodeLane nodeLane;
    private final UpstreamStream stream;
    private final EventData eventData;
    private final LogBook logBook;
    private final TeamAssignments teamAssignments;
    private final StageStartTimes stageStartTimes;
    private final UniqueTeamRegistrations uniqueTeamRegistrations;
    private final AtomicBoolean inputTaskScheduled = new AtomicBoolean();

    /*
     * Highest contiguous RACE_DATA sequence applied to live node state.
     * Only the TimingNode serial lane changes this value. It is protocol state,
     * not a public read model.
     */
    private long lastAppliedRaceDataSequence;

    /*
     * TIMING_DATA replay is bounded and cooperative. One node task sends one
     * batch; the next batch is appended to the back of the same serial lane.
     */
    private boolean replayActive;
    private long replayNextSequence;
    private long replayCapturedHead;

    /*
     * Heartbeat scheduling is supplied by runtime composition, while heartbeat
     * state changes remain serialized with other node work.
     */
    private long heartbeatRequestsSent;
    private long heartbeatAnswersReceived;
    private long heartbeatMissedIntervals;
    private boolean heartbeatAnswerPending;
    private String heartbeatPingId;

    public UpstreamMessagePort(
            NodeId nodeId,
            NodeLane nodeLane,
            UpstreamStream stream,
            EventData eventData,
            LogBook logBook,
            TeamAssignments teamAssignments,
            StageStartTimes stageStartTimes,
            UniqueTeamRegistrations uniqueTeamRegistrations) {
        checkArgument(nodeId != null, "nodeId must not be null");
        checkArgument(nodeLane != null, "nodeLane must not be null");
        checkArgument(stream != null, "stream must not be null");
        checkArgument(eventData != null, "eventData must not be null");
        checkArgument(logBook != null, "logBook must not be null");
        checkArgument(teamAssignments != null, "teamAssignments must not be null");
        checkArgument(stageStartTimes != null, "stageStartTimes must not be null");
        checkArgument(uniqueTeamRegistrations != null, "uniqueTeamRegistrations must not be null");
        this.nodeId = nodeId;
        this.nodeLane = nodeLane;
        this.stream = stream;
        this.eventData = eventData;
        this.logBook = logBook;
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
        if (!stream.isAvailable() || !stream.hasMessage()) {
            return false;
        }
        if (!inputTaskScheduled.compareAndSet(false, true)) {
            return true;
        }

        if (nodeLane.offer(new UpstreamMessageTask(this))) {
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
            if (!stream.isAvailable()) {
                replayActive = false;
                return;
            }

            if (replayActive) {
                processReplayBatchOnNodeLane();
                return;
            }

            if (!stream.hasMessage()) {
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
        checkArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s", data.family());

        UpstreamData.NodeScoped nodeData = (UpstreamData.NodeScoped) data;
        checkArgument(nodeId.equals(nodeData.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                checkArgument(data instanceof UpstreamData.RaceData,
                        "RACE_DATA has incompatible semantic type");
                processRaceDataOnNodeLane((UpstreamData.RaceData) data);
                return;

            case SYNC:
                checkArgument(data instanceof UpstreamData.Sync,
                        "SYNC has incompatible semantic type");
                processSyncOnNodeLane((UpstreamData.Sync) data);
                return;

            case REQ:
                checkArgument(data instanceof UpstreamData.Req,
                        "REQ has incompatible semantic type");
                processReqOnNodeLane((UpstreamData.Req) data);
                return;

            case PING:
                checkArgument(data instanceof UpstreamData.Ping,
                        "PING has incompatible semantic type");
                processPingOnNodeLane((UpstreamData.Ping) data);
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
        long receivedSequence = raceData.sequence();
        checkArgument(receivedSequence >= 1L && receivedSequence <= TimingData.MAX_SEQUENCE_NUMBER,
                "RACE_DATA sequence is outside the supported range");

        if (receivedSequence <= lastAppliedRaceDataSequence) {
            sendRaceDataAck(lastAppliedRaceDataSequence);
            return;
        }

        long expectedSequence = lastAppliedRaceDataSequence + 1L;
        if (receivedSequence != expectedSequence) {
            stream.send(
                    DefaultUpstreamData.req(
                            nodeId,
                            UpstreamData.Stream.RACE_DATA,
                            expectedSequence));
            return;
        }

        /*
         * Apply one RACE_DATA message transactionally. Work is done on copies;
         * live node data changes only after every change has validated.
         */
        TeamAssignments updatedAssignments = teamAssignments.copy();
        StageStartTimes updatedStartTimes = stageStartTimes.copy();

        applyTeamAssignmentChanges(updatedAssignments, raceData);
        applyStageStartChanges(updatedStartTimes, raceData);
        updatedAssignments.validate();

        teamAssignments.replaceWith(updatedAssignments);
        stageStartTimes.replaceWith(updatedStartTimes);
        lastAppliedRaceDataSequence = receivedSequence;

        sendRaceDataAck(receivedSequence);
    }

    private void sendRaceDataAck(
            long sequence) {
        stream.send(
                DefaultUpstreamData.ack(
                        nodeId,
                        UpstreamData.Stream.RACE_DATA,
                        sequence));
    }

    /**
     * Advertises the current committed TIMING_DATA source head through the
     * node-scoped stream. An empty LogBook advertises sequence 0.
     */
    public void sendTimingDataSync() {
        onNodeLane(
                () -> {
                    if (stream.isAvailable()) {
                        stream.send(
                                DefaultUpstreamData.sync(
                                        nodeId,
                                        UpstreamData.Stream.TIMING_DATA,
                                        timingDataHeadOnNodeLane()));
                    }
                    return null;
                },
                "sendTimingDataSync");
    }

    /** Returns the current committed TIMING_DATA source head. */
    public long timingDataHead() {
        return onNodeLane(
                () -> Long.valueOf(
                        timingDataHeadOnNodeLane()),
                "timingDataHead")
                .longValue();
    }

    private long timingDataHeadOnNodeLane() {
        return logBook.headSequence();
    }

    /**
     * Reconciles Central's RACE_DATA source head with the highest contiguous
     * sequence already applied locally. Missing data is requested from the first
     * gap; Central moving backwards is treated as a protocol/state conflict.
     */
    private void processSyncOnNodeLane(UpstreamData.Sync sync) {
        if (sync.stream() != UpstreamData.Stream.RACE_DATA) {
            throw new UnsupportedOperationException(
                    "Inbound SYNC supports RACE_DATA only");
        }

        long sourceHead = sync.sequence();
        checkArgument(sourceHead >= 0L && sourceHead <= TimingData.MAX_SEQUENCE_NUMBER,
                "SYNC sequence is outside the supported range");

        if (sourceHead == lastAppliedRaceDataSequence) {
            return;
        }

        if (sourceHead > lastAppliedRaceDataSequence) {
            stream.send(
                    DefaultUpstreamData.req(
                            nodeId,
                            UpstreamData.Stream.RACE_DATA,
                            lastAppliedRaceDataSequence + 1L));
            return;
        }

        throw new IllegalStateException(
                "Central RACE_DATA source head "
                        + sourceHead
                        + " is behind already applied sequence "
                        + lastAppliedRaceDataSequence);
    }

    /**
     * Starts one TIMING_DATA replay from the requested sequence.
     *
     * <p>The current LogBook head is captured once. Later commits are not added
     * to this replay; Central can request them after the next SYNC.</p>
     */
    private void processReqOnNodeLane(UpstreamData.Req req) {
        if (req.stream() != UpstreamData.Stream.TIMING_DATA) {
            throw new UnsupportedOperationException(
                    "Inbound REQ supports TIMING_DATA only");
        }

        long fromSequence = req.fromSequence();
        checkArgument(fromSequence >= 1L && fromSequence <= TimingData.MAX_SEQUENCE_NUMBER,
                "REQ fromSequence is outside the supported range");

        long capturedHead = timingDataHeadOnNodeLane();
        if (fromSequence > capturedHead) {
            replayActive = false;
            return;
        }

        replayNextSequence = fromSequence;
        replayCapturedHead = capturedHead;
        replayActive = true;
    }

    /**
     * Sends at most one bounded replay batch.
     *
     * <p>Only references to existing LogBook records are collected. The next
     * batch is scheduled as a new node-lane turn so ordinary node work can run
     * between large replay batches.</p>
     */
    private void processReplayBatchOnNodeLane() {
        int limit = (int) Math.min((long) REPLAY_BATCH_SIZE,
                replayCapturedHead - replayNextSequence + 1L);
        List<TimingData> records = new ArrayList<TimingData>(limit);
        logBook.visitRange(replayNextSequence, limit, records::add);

        if (records.isEmpty()) {
            replayActive = false;
        }
        checkState(!records.isEmpty(),
                "LogBook head %s advertised record %s but replay returned no data",
                replayCapturedHead, replayNextSequence);

        TimingData first = records.get(0);
        if (first.sequenceNumber() != replayNextSequence) {
            replayActive = false;
        }
        checkState(first.sequenceNumber() == replayNextSequence,
                "TimingData replay expected sequence %s but got %s",
                replayNextSequence, first.sequenceNumber());

        TimingData last = records.get(records.size() - 1);
        if (last.sequenceNumber() > replayCapturedHead) {
            replayActive = false;
        }
        checkState(last.sequenceNumber() <= replayCapturedHead,
                "TimingData replay crossed captured head %s", replayCapturedHead);

        stream.send(
                DefaultUpstreamData.timingData(
                        nodeId,
                        records));

        if (last.sequenceNumber() >= replayCapturedHead) {
            replayActive = false;
            return;
        }

        replayNextSequence = last.sequenceNumber() + 1L;
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

    private HeartbeatStatus heartbeatStatusOnNodeLane() {
        return new HeartbeatStatus(
                heartbeatRequestsSent,
                heartbeatAnswersReceived,
                heartbeatMissedIntervals,
                heartbeatAnswerPending);
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
        heartbeatPingId = "hb-" + heartbeatRequestsSent;

        stream.send(
                DefaultUpstreamData.ping(
                        nodeId,
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
                            nodeId,
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

    /** Resolves the current TeamId using EventData first and live assignments as fallback. */
    public TeamId teamIdFor(RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        return onNodeLane(() -> resolveTeamId(registrationId), "upstreamTeamIdFor");
    }

    /** Reads the current Central-provided stage start on the owning node lane. */
    public TimingTimestamp stageStartTimeFor(TeamId teamId) {
        return onNodeLane(() -> stageStartTimes.startFor(teamId), "upstreamStageStartTimeFor");
    }

    /** Returns the current unique-team count derived from active registrations. */
    public int uniqueTeamRegistrationCount() {
        return onNodeLane(
                () -> uniqueTeamRegistrations.uniqueTeamCount(this::resolveTeamId),
                "uniqueTeamRegistrationCount");
    }

    private void applyTeamAssignmentChanges(
            TeamAssignments updatedAssignments,
            UpstreamData.RaceData raceData) {
        checkArgument(raceData.teamAssignmentChanges() != null,
                "team assignment changes must not be null");

        for (UpstreamData.TeamAssignmentChange change
                : raceData.teamAssignmentChanges()) {
            checkArgument(change != null, "team assignment change must not be null");

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
        checkArgument(raceData.stageStartTimeChanges() != null,
                "stage start changes must not be null");

        for (UpstreamData.StageStartTimeChange change
                : raceData.stageStartTimeChanges()) {
            checkArgument(change != null, "stage start change must not be null");

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

    private TeamId resolveTeamId(RegistrationId registrationId) {
        TeamId fixed = eventData.teamIdFor(registrationId);
        return fixed != null
                ? fixed
                : teamAssignments.teamIdFor(registrationId);
    }

    /**
     * Keeps one upstream task pending while more input is already queued.
     */
    private void scheduleNextTurnOrRelease() {
        if (stream.isAvailable()
                && (replayActive
                        || stream.hasMessage())
                && nodeLane.offer(new UpstreamMessageTask(this))) {
            return;
        }

        inputTaskScheduled.set(false);
    }

    private <R> R onNodeLane(Callable<R> task, String operation) {
        return nodeLane.run(task, operation);
    }

    /**
     * Narrow execution boundary supplied by the owning TimingNode.
     *
     * <p>The upstream package never receives the SerialExecutor itself and does
     * not own another worker. Both operations target the existing TimingNode
     * serial lane.</p>
     */
    public interface NodeLane {
        boolean offer(Runnable task);

        <R> R run(Callable<R> task, String operation);
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
