package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeDataLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingNode-owned semantic boundary for node-scoped upstream communication.
 *
 * <p>The handler coordinates two TimingNode-owned logical lanes. Stream turns
 * are currently queued on the Data Lane so TIMING_DATA LogBook access stays
 * ordered there. RACE_DATA, heartbeat and their live protocol/state work are
 * handed to the Task Lane. Protocol state is deliberately split into
 * {@link UpstreamHeartbeat}, {@link UpstreamDataSync} and {@link LogBookSync}.
 * Transport/session I/O belongs to the I/O-layer {@link UpstreamStream}; none of
 * these domain helpers owns a transport thread or executor.</p>
 */
public final class UpstreamHandler {
    private static final Logger LOG = LoggerFactory.getLogger(UpstreamHandler.class);

    private final NodeId nodeId;
    private final TimingNodeDataLane dataLane;
    private final SerialTaskRunner taskRunner;
    private final UpstreamStream stream;
    private final UpstreamHeartbeat heartbeat;
    private final UpstreamDataSync upstreamDataSync;
    private final LogBookSync logBookSync;

    /*
     * inputAvailable() may be called by an I/O thread. This flag prevents a
     * burst of notifications from enqueueing duplicate node operations. The flag is
     * released only when no inbound message or LogBook synchronization batch remains.
     */
    private final AtomicBoolean inputOperationScheduled = new AtomicBoolean();

    /** Creates the node-scoped upstream coordinator over the composed Data/Task Lane roles. */
    public UpstreamHandler(
            NodeId nodeId,
            TimingNodeDataLane dataLane,
            SerialTaskRunner taskRunner,
            UpstreamStream stream,
            LogBookAccessor logBook,
            UpstreamTaskState taskState) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(dataLane != null, "dataLane must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(taskState != null, "taskState must not be null");

        this.nodeId = nodeId;
        this.dataLane = dataLane;
        this.taskRunner = taskRunner;
        this.stream = stream;
        this.heartbeat = new UpstreamHeartbeat(nodeId, stream);
        this.upstreamDataSync = new UpstreamDataSync(nodeId, stream, taskState);
        this.logBookSync = new LogBookSync(nodeId, stream, logBook);
        dataLane.bindUpstream(this::handleNextTurn);
    }

    /**
     * Signals that the node-scoped stream may contain inbound data.
     *
     * <p>The caller never performs semantic work or waits for the TimingNode.
     * At most one upstream operation is pending/running for this port.</p>
     */
    public boolean inputAvailable() {
        if (!stream.isAvailable() || !stream.hasMessage()) {
            return false;
        }
        if (!inputOperationScheduled.compareAndSet(false, true)) {
            return true;
        }

        SerialLane.OfferResult result = dataLane.offerUpstreamTurn();
        if (result == SerialLane.OfferResult.ACCEPTED) {
            return true;
        }

        inputOperationScheduled.set(false);
        return false;
    }

    /**
     * Handles one queued upstream data/replay turn.
     *
     * <p>A turn handles either one inbound message or sends one LogBook
     * synchronization batch. Each batch contains at most 256 records. Remaining
     * work is appended to the back of the same lane so synchronization cannot
     * starve registrations or other node work.</p>
     */
    void handleNextTurn() {
        try {
            if (!stream.isAvailable()) {
                logBookSync.cancelSync();
            } else if (logBookSync.syncPending()) {
                logBookSync.sendNextBatch();
            } else if (stream.hasMessage()) {
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
        failIfInvalidArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s", data.family());

        UpstreamData.NodeScoped nodeScoped = (UpstreamData.NodeScoped) data;
        failIfInvalidArgument(nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                failIfInvalidArgument(data instanceof UpstreamData.RaceData,
                        "RACE_DATA has incompatible semantic type");
                UpstreamData.RaceData raceData = (UpstreamData.RaceData) data;
                Runnable applyRaceData = () -> upstreamDataSync.handleRaceData(raceData);
                executeTaskOrWarn(applyRaceData, "RACE_DATA");
                break;

            case SYNC:
                failIfInvalidArgument(data instanceof UpstreamData.Sync,
                        "SYNC has incompatible semantic type");
                UpstreamData.Sync sync = (UpstreamData.Sync) data;
                if (sync.stream() == UpstreamData.Stream.RACE_DATA) {
                    Runnable applyRaceDataSync = () -> upstreamDataSync.handleSync(sync);
                    executeTaskOrWarn(applyRaceDataSync, "RACE_DATA SYNC");
                } else {
                    throw new UnsupportedOperationException(
                            "Inbound SYNC supports RACE_DATA only");
                }
                break;

            case REQ:
                failIfInvalidArgument(data instanceof UpstreamData.Req,
                        "REQ has incompatible semantic type");
                logBookSync.handleReq((UpstreamData.Req) data);
                break;

            case PING:
                failIfInvalidArgument(data instanceof UpstreamData.Ping,
                        "PING has incompatible semantic type");
                UpstreamData.Ping ping = (UpstreamData.Ping) data;
                Runnable handlePing = () -> heartbeat.handle(ping);
                executeTaskOrWarn(handlePing, "PING");
                break;

            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by TimingNode: " + data.family());
        }
    }

    /**
     * Sends a TIMING_DATA SYNC using the directly published LogBook head.
     *
     * <p>No Data Lane hop is needed: LogBook publishes its scalar head for
     * direct reads and UpstreamStream.send only enqueues into the local Messaging
     * mailbox.</p>
     */
    public void sendTimingDataSync() {
        logBookSync.sendSync();
    }

    /**
     * Queues one externally scheduled heartbeat tick on the Task Lane.
     *
     * <p>This handoff is deliberately non-blocking because Data and Task Lane may
     * share one physical worker. Returning from this method does not mean the
     * heartbeat tick has executed.</p>
     */
    public void heartbeatTick() {
        executeTaskOrWarn(heartbeat::tick, "heartbeat tick");
    }

    /** Returns the latest immutable heartbeat snapshot without a lane hop. */
    public UpstreamHandlerTypes.HeartbeatStatus heartbeatStatus() {
        return heartbeat.status();
    }

    /** Executes one Task Lane operation and contains rejection at the upstream callback boundary. */
    private void executeTaskOrWarn(Runnable task, String operation) {
        Runnable queueTask =
                () ->
                        taskRunner.runTask(
                                () -> {
                                    task.run();
                                    return TaskStep.done();
                                });
        Consumer<RejectedExecutionException> onRejected = ex -> LOG.warn(
                "Dropped upstream {} for node {} because the Task Lane rejected execution",
                operation,
                nodeId.value(),
                ex);

        SafeExecute.runOrHandleRejection(queueTask, onRejected);
    }

    /**
     * Keeps exactly one upstream operation pending while work remains.
     */
    private void scheduleNextTurnOrRelease() {
        boolean workRemaining = stream.isAvailable() && (logBookSync.syncPending() || stream.hasMessage());
        boolean nextTurnScheduled = false;

        if (workRemaining) {
            SerialLane.OfferResult result = dataLane.offerUpstreamTurn();
            nextTurnScheduled = result == SerialLane.OfferResult.ACCEPTED;
            if (!nextTurnScheduled) {
                LOG.debug(
                        "Data Lane rejected another upstream turn for node {}: {}",
                        nodeId.value(),
                        result);
            }
        }

        if (!nextTurnScheduled) {
            inputOperationScheduled.set(false);
        }
    }


}
