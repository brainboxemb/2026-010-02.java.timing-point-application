package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingSystem-owned semantic boundary for one node's upstream communication.
 *
 * <p>Each node-specific instance owns its own logical protocol lane on the
 * Runtime-supplied shared scheduler.
 * TIMING_DATA synchronization submits ordered reads to the LogBook Data Lane
 * and yields its protocol lane with {@link TaskStep#await} until those reads
 * complete. Transport/session I/O belongs to {@link UpstreamStream}; none of
 * these domain helpers owns a transport thread or executor.</p>
 */
public final class UpstreamHandler extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(UpstreamHandler.class);
    private static final int TASK_QUEUE_CAPACITY = 32;
    private static final int TASK_MAX_ITEMS_PER_RUN = 32;

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
            ScheduledExecutorService scheduler,
            UpstreamStream stream,
            LogBookAccessor logBook,
            Function<UpstreamData.RaceData, CompletionStage<Void>> raceDataConsumer) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(raceDataConsumer != null, "raceDataConsumer must not be null");

        this.nodeId = nodeId;
        this.taskRunner =
                new SerialTaskRunner(
                        TASK_QUEUE_CAPACITY,
                        TASK_MAX_ITEMS_PER_RUN,
                        "Upstream-" + nodeId.value(),
                        scheduler);
        this.stream = stream;
        this.heartbeat = new UpstreamHeartbeat(nodeId, stream);
        this.upstreamDataSync = new UpstreamDataSync(nodeId, stream, raceDataConsumer);
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

    /** Returns this node-specific protocol instance identity. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Starts this node's protocol-owned logical lane. */
    @Override
    protected void onActivate() {
        taskRunner.start();
    }

    /** Cancels protocol work and closes this node's logical lane. */
    @Override
    protected void onDeactivate() {
        inputTask.cancel();
        logBookSync.cancelSync();
        upstreamDataSync.cancelPending();
        taskRunner.close();
    }

    /** Handles one semantic upstream message while the protocol lane is current. */
    private CompletionStage<Void> handleMessage(UpstreamData data) {
        failIfInvalidArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s", data.family());

        UpstreamData.NodeScoped nodeScoped = (UpstreamData.NodeScoped) data;
        failIfInvalidArgument(nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                failIfInvalidArgument(data instanceof UpstreamData.RaceData,
                        "RACE_DATA has incompatible semantic type");
                return upstreamDataSync.handleRaceData(
                        (UpstreamData.RaceData) data);

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
        return null;
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
        private CompletableFuture<List<TimingRecord>> pendingTimingData;
        private UpstreamData.RaceData pendingRaceData;
        private CompletionStage<Void> pendingRaceDataApply;

        @Override
        protected void resetForRun() {
            pendingTimingData = null;
            pendingRaceData = null;
            pendingRaceDataApply = null;
        }

        @Override
        public TaskStep runStep() {
            if (!stream.isAvailable()) {
                pendingTimingData = null;
                pendingRaceData = null;
                pendingRaceDataApply = null;
                logBookSync.cancelSync();
                upstreamDataSync.cancelPending();
                return TaskStep.done();
            }

            if (pendingRaceDataApply != null) {
                pendingRaceDataApply.toCompletableFuture().join();
                long sequence = pendingRaceData.sequence();
                pendingRaceData = null;
                pendingRaceDataApply = null;
                upstreamDataSync.completeRaceData(sequence);
                return TaskStep.again();
            }

            if (pendingTimingData != null) {
                List<TimingRecord> records = pendingTimingData.join();
                pendingTimingData = null;
                logBookSync.sendNextBatch(records);
                return TaskStep.again();
            }

            if (logBookSync.syncPending()) {
                pendingTimingData = logBookSync.submitNextBatch();
                return TaskStep.await(pendingTimingData);
            }

            if (stream.hasMessage()) {
                UpstreamData message = stream.read();
                CompletionStage<Void> completion =
                        handleMessage(message);
                if (completion != null) {
                    pendingRaceData =
                            (UpstreamData.RaceData) message;
                    pendingRaceDataApply = completion;
                    return TaskStep.await(completion);
                }
                return TaskStep.again();
            }

            return TaskStep.done();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            pendingTimingData = null;
            pendingRaceData = null;
            pendingRaceDataApply = null;
            if (failure != null) {
                logBookSync.cancelSync();
                upstreamDataSync.cancelPending();
                LOG.warn(
                        "Upstream input task failed for node {}",
                        nodeId.value(),
                        failure);
            }
        }
    }
}
