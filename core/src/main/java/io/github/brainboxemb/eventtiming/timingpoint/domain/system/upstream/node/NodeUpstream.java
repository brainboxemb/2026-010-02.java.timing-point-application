package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

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
public final class NodeUpstream extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeUpstream.class);
    private static final int TASK_QUEUE_CAPACITY = 32;
    private static final int TASK_MAX_ITEMS_PER_RUN = 32;

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final UpstreamStream stream;
    private final UpstreamHeartbeat heartbeat;
    private final UpstreamDataSync upstreamDataSync;
    private final LogBookSync logBookSync;
    private final InputTask inputTask = new InputTask();
    private CompletableFuture<Void> activeInputOperation;

    /** Creates one node's upstream protocol state and independent logical task lane. */
    public NodeUpstream(
            NodeId nodeId,
            ScheduledExecutorService scheduler,
            UpstreamStream stream,
            LogBookAccessor logBook,
            NodeService nodeService) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");

        this.nodeId = nodeId;
        this.taskRunner =
                new SerialTaskRunner(
                        TASK_QUEUE_CAPACITY,
                        TASK_MAX_ITEMS_PER_RUN,
                        "Upstream-" + nodeId.value(),
                        scheduler);
        this.stream = stream;
        this.heartbeat = new UpstreamHeartbeat(nodeId, stream);
        this.upstreamDataSync = new UpstreamDataSync(nodeId, stream, nodeService::applyRaceData);
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
        cancelActiveInputOperation();
        logBookSync.cancelSync();
        upstreamDataSync.cancelPending();
        taskRunner.close();
    }

    /** Creates one focused task for one inbound semantic message. */
    private YieldingTask inputOperation(UpstreamData data) {
        failIfInvalidArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s", data.family());

        UpstreamData.NodeScoped nodeScoped = (UpstreamData.NodeScoped) data;
        failIfInvalidArgument(nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                failIfInvalidArgument(data instanceof UpstreamData.RaceData,
                        "RACE_DATA has incompatible semantic type");
                return new RaceDataTask(
                        stream,
                        upstreamDataSync,
                        (UpstreamData.RaceData) data);

            case REQ:
                failIfInvalidArgument(data instanceof UpstreamData.Req,
                        "REQ has incompatible semantic type");
                return new TimingDataSyncTask(
                        stream,
                        logBookSync,
                        (UpstreamData.Req) data);

            case SYNC:
                failIfInvalidArgument(data instanceof UpstreamData.Sync,
                        "SYNC has incompatible semantic type");
                return oneStepTask(
                        () -> upstreamDataSync.handleSync(
                                (UpstreamData.Sync) data));

            case PING:
                failIfInvalidArgument(data instanceof UpstreamData.Ping,
                        "PING has incompatible semantic type");
                return oneStepTask(
                        () -> heartbeat.handle(
                                (UpstreamData.Ping) data));

            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by NodeUpstream: "
                                + data.family());
        }
    }

    /** Wraps one synchronous protocol operation as one ordinary task-run. */
    private YieldingTask oneStepTask(Runnable operation) {
        return () -> {
            if (stream.isAvailable()) {
                operation.run();
            }
            return TaskStep.done();
        };
    }

    /** Starts exactly one inbound operation; the next message waits for its completion. */
    private void startInputOperation(YieldingTask operation) {
        CompletableFuture<Void> run = taskRunner.runTask(operation);
        synchronized (this) {
            if (activeInputOperation != null) {
                run.cancel(true);
                throw new IllegalStateException(
                        "NodeUpstream already has an active input operation");
            }
            activeInputOperation = run;
        }
        run.whenComplete(
                (ignored, failure) ->
                        inputOperationCompleted(run, failure));
    }

    private void inputOperationCompleted(
            CompletableFuture<Void> run,
            Throwable failure) {
        synchronized (this) {
            if (activeInputOperation != run) {
                return;
            }
            activeInputOperation = null;
        }

        if (failure != null) {
            logBookSync.cancelSync();
            upstreamDataSync.cancelPending();
            LOG.warn(
                    "Upstream input operation failed for node {}",
                    nodeId.value(),
                    failure);
        }

        if (taskRunner.isRunning()
                && stream.isAvailable()
                && stream.hasMessage()) {
            inputTask.wake(taskRunner);
        }
    }

    private synchronized boolean inputOperationActive() {
        return activeInputOperation != null;
    }

    private synchronized void cancelActiveInputOperation() {
        CompletableFuture<Void> run = activeInputOperation;
        activeInputOperation = null;
        if (run != null) {
            run.cancel(true);
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
    public NodeUpstreamTypes.HeartbeatStatus heartbeatStatus() {
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
     * Admits one queued message as one focused task.
     *
     * <p>The input task owns no protocol continuation state. A task that must
     * wait for NodeService or LogBook completion owns that wait itself. The next
     * inbound message is admitted only after the active message task completes.</p>
     */
    private final class InputTask extends AbstractTask {
        @Override
        protected void resetForRun() {
            // No operation state: this task only admits one message.
        }

        @Override
        public TaskStep runStep() {
            if (!stream.isAvailable()
                    || inputOperationActive()
                    || !stream.hasMessage()) {
                return TaskStep.done();
            }

            UpstreamData message = stream.read();
            startInputOperation(
                    inputOperation(message));
            return TaskStep.done();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            if (failure != null) {
                LOG.warn(
                        "Upstream input admission failed for node {}",
                        nodeId.value(),
                        failure);
            }
        }
    }
}