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
 * <p>Each node-specific instance owns one logical protocol task runner on the
 * Runtime-supplied shared scheduler. The node-scoped {@link UpstreamStream}
 * owns the inbound message queue. NodeUpstream admits exactly one queued
 * message task at a time. While that task awaits NodeService or LogBook work,
 * later messages remain in the stream queue; they are not discarded. When the
 * active message task completes, the next queued message is admitted.</p>
 *
 * <p>Protocol duplicates are different from queued duplicates. For example,
 * a repeated RACE_DATA sequence is read normally and RaceDataSync decides
 * whether to ACK it again or request a missing sequence. Transport notification
 * coalescing never removes an UpstreamData message from the input queue.</p>
 */
public final class NodeUpstream extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeUpstream.class);
    private static final int TASK_QUEUE_CAPACITY = 32;
    private static final int TASK_MAX_ITEMS_PER_RUN = 32;

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final UpstreamStream stream;
    private final NodeService nodeService;
    private final Heartbeat heartbeat;
    private final RaceDataSync raceDataSync;
    private final TimingDataSync timingDataSync;
    private final InputTask inputTask = new InputTask();
    private CompletableFuture<Void> activeMessageRun;

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
        this.nodeService = nodeService;
        this.heartbeat = new Heartbeat(nodeId, stream);
        this.raceDataSync = new RaceDataSync(nodeId, stream);
        this.timingDataSync = new TimingDataSync(nodeId, stream, logBook);
    }

    /**
     * Signals that the node-scoped stream contains inbound data.
     *
     * <p>The caller performs no semantic work and never waits for the node.
     * Repeated notifications may coalesce, but messages stay in UpstreamStream
     * until NodeUpstream reads them. When a message task is already active this
     * method simply retains the notification by leaving the queued data in place.</p>
     */
    public boolean inputAvailable() {
        if (!stream.isAvailable() || !stream.hasMessage()) {
            return false;
        }
        if (messageTaskActive()) {
            return true;
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
        cancelActiveMessageTask();
        timingDataSync.cancelReplay();
        raceDataSync.cancelApply();
        taskRunner.close();
    }

    /** Creates one focused task for one inbound semantic message. */
    private YieldingTask createMessageTask(UpstreamData data) {
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
                        raceDataSync,
                        nodeService,
                        (UpstreamData.RaceData) data);

            case REQ:
                failIfInvalidArgument(data instanceof UpstreamData.Req,
                        "REQ has incompatible semantic type");
                return new TimingDataReplayTask(
                        stream,
                        timingDataSync,
                        (UpstreamData.Req) data);

            case SYNC:
                failIfInvalidArgument(data instanceof UpstreamData.Sync,
                        "SYNC has incompatible semantic type");
                return oneStepTask(
                        () -> raceDataSync.handleSync(
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

    /** Starts exactly one message task; later input remains queued until it completes. */
    private void startMessageTask(YieldingTask task) {
        CompletableFuture<Void> run = taskRunner.runTask(task);
        synchronized (this) {
            if (activeMessageRun != null) {
                run.cancel(true);
                throw new IllegalStateException(
                        "NodeUpstream already has an active input operation");
            }
            activeMessageRun = run;
        }
        run.whenComplete(
                (ignored, failure) ->
                        messageTaskCompleted(run, failure));
    }

    /** Clears one completed message task and then admits queued follow-up input. */
    private void messageTaskCompleted(
            CompletableFuture<Void> run,
            Throwable failure) {
        synchronized (this) {
            if (activeMessageRun != run) {
                return;
            }
            activeMessageRun = null;
        }

        if (failure != null) {
            timingDataSync.cancelSync();
            raceDataSync.cancelPending();
            LOG.warn(
                    "Upstream input operation failed for node {}",
                    nodeId.value(),
                    failure);
        }

        wakeQueuedInput();
    }

    private synchronized boolean messageTaskActive() {
        return activeMessageRun != null;
    }

    /** Wakes admission after a message task completes while leaving queue ownership to the stream. */
    private void wakeQueuedInput() {
        if (taskRunner.isRunning()
                && stream.isAvailable()
                && stream.hasMessage()) {
            inputTask.wake(taskRunner);
        }
    }

    private synchronized void cancelActiveMessageTask() {
        CompletableFuture<Void> run = activeMessageRun;
        activeMessageRun = null;
        if (run != null) {
            run.cancel(true);
        }
    }

    /** Queues one TIMING_DATA source-head publication on the Task Lane. */
    public void sendTimingDataSync() {
        executeTaskOrWarn(timingDataSync::sendSourceHead, "TIMING_DATA SYNC");
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
                    || messageTaskActive()
                    || !stream.hasMessage()) {
                return TaskStep.done();
            }

            UpstreamData message = stream.read();
            startMessageTask(createMessageTask(message));
            return TaskStep.done();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            if (failure != null) {
                LOG.warn(
                        "Upstream input admission failed for node {}",
                        nodeId.value(),
                        failure);
                wakeQueuedInput();
            }
        }
    }
}