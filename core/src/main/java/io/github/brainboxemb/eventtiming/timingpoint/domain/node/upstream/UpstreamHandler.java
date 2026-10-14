package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookReader;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * TimingNode-owned semantic boundary for node-scoped upstream communication.
 *
 * <p>The handler coordinates two TimingNode-owned logical lanes. Stream turns
 * are currently admitted on the Data Lane so TIMING_DATA LogBook access stays
 * ordered there. RACE_DATA, heartbeat and their live protocol/state work are
 * handed to the Task Lane. Protocol state is deliberately split into
 * {@link UpstreamHeartbeat}, {@link UpstreamDataSync} and {@link LogBookSync}.
 * Transport/session I/O belongs to the I/O-layer {@link UpstreamStream}; none of
 * these domain helpers owns a transport thread or executor.</p>
 */
public final class UpstreamHandler {
    private static final Logger LOG =
            LoggerFactory.getLogger(UpstreamHandler.class);

    private final NodeId nodeId;
    private final DataLane dataLane;
    private final TaskLane taskLane;
    private final UpstreamStream stream;
    private final UpstreamHeartbeat heartbeat;
    private final UpstreamDataSync upstreamDataSync;
    private final LogBookSync logBookSync;
    private final LogBookReader logBookReader;
    private final UpstreamTaskState taskState;

    /*
     * inputAvailable() may be called by an I/O thread. This flag prevents a
     * burst of notifications from enqueueing duplicate node tasks. The flag is
     * released only when no inbound message or LogBook synchronization batch remains.
     */
    private final AtomicBoolean inputTaskScheduled = new AtomicBoolean();

    public UpstreamHandler(
            NodeId nodeId,
            DataLane dataLane,
            TaskLane taskLane,
            UpstreamStream stream,
            LogBookReader logBookReader,
            UpstreamTaskState taskState) {
        checkArgument(nodeId != null, "nodeId must not be null");
        checkArgument(dataLane != null, "dataLane must not be null");
        checkArgument(taskLane != null, "taskLane must not be null");
        checkArgument(stream != null, "stream must not be null");
        checkArgument(logBookReader != null, "logBookReader must not be null");
        checkArgument(taskState != null, "taskState must not be null");

        this.nodeId = nodeId;
        this.dataLane = dataLane;
        this.taskLane = taskLane;
        this.stream = stream;
        this.logBookReader = logBookReader;
        this.taskState = taskState;
        this.heartbeat = new UpstreamHeartbeat(nodeId, stream);
        this.upstreamDataSync =
                new UpstreamDataSync(nodeId, stream, taskState);
        this.logBookSync = new LogBookSync(nodeId, stream, logBookReader);
    }

    /**
     * Signals that the node-scoped stream may contain inbound data.
     *
     * <p>The caller never performs semantic work or waits for the TimingNode.
     * At most one upstream task is pending/running for this port.</p>
     */
    public boolean inputAvailable() {
        if (!stream.isAvailable() || !stream.hasMessage()) {
            return false;
        }
        if (!inputTaskScheduled.compareAndSet(false, true)) {
            return true;
        }

        if (dataLane.offer(new UpstreamHandlerTask(this))) {
            return true;
        }

        inputTaskScheduled.set(false);
        return false;
    }

    /**
     * Executes one existing upstream data/replay turn on the TimingNode Data Lane.
     *
     * <p>A turn handles either one inbound message or sends one LogBook
     * synchronization batch. Each batch contains at most 256 records. Remaining
     * work is appended to the back of the same lane so synchronization cannot
     * starve registrations or other node work.</p>
     */
    void handleNextOnDataLane() {
        try {
            if (!stream.isAvailable()) {
                logBookSync.cancelSync();
                return;
            }

            if (logBookSync.syncPending()) {
                logBookSync.sendNextBatch();
                return;
            }

            if (stream.hasMessage()) {
                handleMessage(stream.read());
            }
        } finally {
            scheduleNextTurnOrRelease();
        }
    }

    /**
     * Handles one semantic upstream message by delegating it to the helper that
     * owns that message family's state and rules.
     */
    private void handleMessage(UpstreamData data) {
        checkArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s", data.family());

        UpstreamData.NodeScoped nodeScoped = (UpstreamData.NodeScoped) data;
        checkArgument(nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                checkArgument(data instanceof UpstreamData.RaceData,
                        "RACE_DATA has incompatible semantic type");
                offerTaskProtocol(
                        "RACE_DATA",
                        () -> upstreamDataSync.handleRaceData(
                                (UpstreamData.RaceData) data));
                return;

            case SYNC:
                checkArgument(data instanceof UpstreamData.Sync,
                        "SYNC has incompatible semantic type");
                UpstreamData.Sync sync = (UpstreamData.Sync) data;
                if (sync.stream() == UpstreamData.Stream.RACE_DATA) {
                    offerTaskProtocol(
                            "RACE_DATA SYNC",
                            () -> upstreamDataSync.handleSync(sync));
                } else {
                    throw new UnsupportedOperationException(
                            "Inbound SYNC supports RACE_DATA only");
                }
                return;

            case REQ:
                checkArgument(data instanceof UpstreamData.Req,
                        "REQ has incompatible semantic type");
                logBookSync.handleReq((UpstreamData.Req) data);
                return;

            case PING:
                checkArgument(data instanceof UpstreamData.Ping,
                        "PING has incompatible semantic type");
                UpstreamData.Ping ping =
                        (UpstreamData.Ping) data;
                if (!taskLane.offer(
                        () -> heartbeat.handle(ping))) {
                    LOG.warn(
                            "Dropped upstream PING for node {} because the Task Lane could not accept it",
                            nodeId.value());
                }
                return;

            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by TimingNode: " + data.family());
        }
    }

    /**
     * Sends a TIMING_DATA SYNC using the directly published LogBook head.
     *
     * <p>No TimingNode-lane hop is needed: LogBook publishes its scalar head for
     * direct reads and UpstreamStream.send only enqueues into the local Messaging
     * mailbox.</p>
     */
    public void sendTimingDataSync() {
        logBookSync.sendSync();
    }

    /**
     * Offers one externally scheduled heartbeat tick to the TimingNode Task Lane.
     *
     * <p>This handoff is deliberately non-blocking because Data and Task Lane may
     * share one physical worker.</p>
     */
    public void heartbeatTick() {
        if (!taskLane.offer(heartbeat::tick)) {
            LOG.warn(
                    "Skipped heartbeat tick for node {} because the Task Lane could not accept it",
                    nodeId.value());
        }
    }

    /** Returns the latest immutable heartbeat snapshot without a lane hop. */
    public UpstreamHandlerTypes.HeartbeatStatus heartbeatStatus() {
        return heartbeat.status();
    }

    /** Resolves current TeamId on the Task Lane. */
    public TeamId teamIdFor(RegistrationId registrationId) {
        return onTaskLane(
                () -> taskState.teamIdFor(registrationId),
                "upstreamTeamIdFor");
    }

    /** Reads the current Central-provided stage start on the Task Lane. */
    public TimingTimestamp stageStartTimeFor(TeamId teamId) {
        return onTaskLane(
                () -> taskState.stageStartTimeFor(teamId),
                "upstreamStageStartTimeFor");
    }

    /** Returns the Task Lane-owned unique-team count. */
    public int uniqueTeamRegistrationCount() {
        return onTaskLane(
                () -> Integer.valueOf(taskState.uniqueTeamRegistrationCount()),
                "uniqueTeamRegistrationCount").intValue();
    }

    private void offerTaskProtocol(String operation, Runnable task) {
        if (!taskLane.offer(task)) {
            LOG.warn(
                    "Dropped upstream {} for node {} because the Task Lane could not accept it; no ACK is sent",
                    operation,
                    nodeId.value());
        }
    }

    /**
     * Keeps exactly one upstream task pending while work remains.
     */
    private void scheduleNextTurnOrRelease() {
        boolean workRemaining =
                stream.isAvailable() && (logBookSync.syncPending() || stream.hasMessage());

        if (workRemaining && dataLane.offer(new UpstreamHandlerTask(this))) {
            return;
        }

        inputTaskScheduled.set(false);
    }

    private <R> R onTaskLane(Callable<R> task, String operation) {
        return taskLane.run(task, operation);
    }

    /** Narrow Data Lane execution boundary supplied by the owning TimingNode. */
    public interface DataLane {
        boolean offer(Runnable task);

        void run(Runnable task, String operation);

        <R> R run(Callable<R> task, String operation);
    }

    /**
     * Narrow non-blocking Task Lane boundary.
     *
     * <p>Upstream code may only offer work here; it cannot synchronously wait
     * across lanes that may share the same physical worker.</p>
     */
    public interface TaskLane {
        boolean offer(Runnable task);

        void run(Runnable task, String operation);

        <R> R run(Callable<R> task, String operation);
    }

}
