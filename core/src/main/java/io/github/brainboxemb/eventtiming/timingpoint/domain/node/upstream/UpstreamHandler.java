package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingNode-owned semantic boundary for node-scoped upstream communication.
 *
 * <p>All mutable upstream protocol state runs on TimingNode's Task Lane.
 * TIMING_DATA synchronization submits ordered reads to the LogBook Data Lane
 * and yields the Task Lane with {@link TaskStep#await} until those reads
 * complete. Transport/session I/O belongs to {@link UpstreamStream}; none of
 * these domain helpers owns a transport thread or executor.</p>
 */
public final class UpstreamHandler {
    private static final Logger LOG = LoggerFactory.getLogger(UpstreamHandler.class);

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final UpstreamStream stream;
    private final UpstreamHeartbeat heartbeat;
    private final UpstreamDataSync upstreamDataSync;
    private final LogBookSync logBookSync;
    private final InputTask inputTask = new InputTask();

    /** Creates the node-scoped upstream coordinator over TimingNode's Task Lane. */
    public UpstreamHandler(
            NodeId nodeId,
            SerialTaskRunner taskRunner,
            UpstreamStream stream,
            LogBookAccessor logBook,
            UpstreamTaskState taskState) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(taskState != null, "taskState must not be null");

        this.nodeId = nodeId;
        this.taskRunner = taskRunner;
        this.stream = stream;
        this.heartbeat = new UpstreamHeartbeat(nodeId, stream);
        this.upstreamDataSync = new UpstreamDataSync(nodeId, stream, taskState);
        this.logBookSync = new LogBookSync(nodeId, stream, logBook);
    }

    /**
     * Signals that the node-scoped stream contains inbound data.
     *
     * <p>The caller performs no semantic work and never waits for the node.
     * Repeated notifications coalesce while the reusable input task is active.</p>
     */
    public boolean inputAvailable() {
        if (!stream.isAvailable() || !stream.hasMessage()) {
            return false;
        }
        return inputTask.wake(taskRunner);
    }

    /** Handles one semantic upstream message while the Task Lane is current. */
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
                upstreamDataSync.handleRaceData((UpstreamData.RaceData) data);
                break;

            case SYNC:
                failIfInvalidArgument(data instanceof UpstreamData.Sync,
                        "SYNC has incompatible semantic type");
                UpstreamData.Sync sync = (UpstreamData.Sync) data;
                if (sync.stream() == UpstreamData.Stream.RACE_DATA) {
                    upstreamDataSync.handleSync(sync);
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
                heartbeat.handle((UpstreamData.Ping) data);
                break;

            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by TimingNode: " + data.family());
        }
    }

    /** Queues one TIMING_DATA source-head publication on the Task Lane. */
    public void sendTimingDataSync() {
        executeTaskOrWarn(logBookSync::sendSync, "TIMING_DATA SYNC");
    }

    /** Queues one externally scheduled heartbeat tick on the Task Lane. */
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
     * Reusable inbound/protocol task.
     *
     * <p>One step handles one message, submits one ordered LogBook batch read,
     * or consumes one completed batch. AGAIN always goes to the back of the Task
     * Lane, preserving cooperative fairness with other node tasks.</p>
     */
    private final class InputTask extends AbstractTask {
        private CompletableFuture<List<TimingData>> pendingTimingData;

        @Override
        protected void resetForRun() {
            pendingTimingData = null;
        }

        @Override
        public TaskStep runStep() {
            if (!stream.isAvailable()) {
                pendingTimingData = null;
                logBookSync.cancelSync();
                return TaskStep.done();
            }

            if (pendingTimingData != null) {
                List<TimingData> records = pendingTimingData.join();
                pendingTimingData = null;
                logBookSync.sendNextBatch(records);
                return TaskStep.again();
            }

            if (logBookSync.syncPending()) {
                pendingTimingData = logBookSync.submitNextBatch();
                return TaskStep.await(pendingTimingData);
            }

            if (stream.hasMessage()) {
                handleMessage(stream.read());
                return TaskStep.again();
            }

            return TaskStep.done();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            pendingTimingData = null;
            if (failure != null) {
                logBookSync.cancelSync();
                LOG.warn(
                        "Upstream input task failed for node {}",
                        nodeId.value(),
                        failure);
            }
        }
    }
}
