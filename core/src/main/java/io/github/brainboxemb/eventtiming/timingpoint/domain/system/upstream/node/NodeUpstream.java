package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingSystem-owned semantic endpoint for one node's upstream communication.
 *
 * <p>Messaging delivers encoded messages through one node-scoped
 * {@link UpstreamStream}. This component owns the {@link UpstreamDataCodec}, so
 * semantic IF-06 types do not leak into gateway or connector code.</p>
 *
 * <p>Exactly one decoded inbound protocol operation is active per node. Later
 * encoded messages remain queued in the stream while an earlier yielding task
 * awaits NodeService or LogBook completion.</p>
 */
public final class NodeUpstream extends LifecycleComponent {
    private static final Logger LOG =
            LoggerFactory.getLogger(NodeUpstream.class);
    private static final int TASK_QUEUE_CAPACITY = 32;

    /** Stream connection event input wired during runtime composition. */
    public static final EventInput<NodeUpstream, NodeId> CONNECTED =
            EventInput.of(
                    (upstream, source) ->
                            source.subscribe(upstream::onConnected));

    /** Stream data-available event input wired during runtime composition. */
    public static final EventInput<NodeUpstream, NodeId> DATA_AVAILABLE =
            EventInput.of(
                    (upstream, source) ->
                            source.subscribe(upstream::onDataAvailable));

    /** Stream heartbeat-cadence event input wired during runtime composition. */
    public static final EventInput<NodeUpstream, NodeId> HEARTBEAT_TICK =
            EventInput.of(
                    (upstream, source) ->
                            source.subscribe(upstream::onHeartbeatTick));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final UpstreamStream stream;
    private final UpstreamDataCodec codec;
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
            UpstreamDataCodec codec,
            LogBookAccessor logBook,
            NodeService nodeService) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(codec != null, "codec must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");

        this.nodeId = nodeId;
        this.taskRunner =
                new SerialTaskRunner(
                        TASK_QUEUE_CAPACITY,
                        "Upstream-" + nodeId.value(),
                        scheduler);
        this.stream = stream;
        this.codec = codec;
        this.nodeService = nodeService;
        this.heartbeat = new Heartbeat(nodeId, this);
        this.raceDataSync = new RaceDataSync(nodeId, this);
        this.timingDataSync =
                new TimingDataSync(
                        nodeId,
                        this,
                        logBook);
    }

    /** Returns this node-specific protocol instance identity. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Starts this node's protocol-owned logical lane. */
    @Override
    protected void onActivate() {
        taskRunner.start();
        wakeQueuedInput();
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

    /** Returns whether the node-scoped transport is currently available. */
    boolean isAvailable() {
        return stream.isAvailable();
    }

    /** Encodes and sends one semantic IF-06 message. */
    void send(
            UpstreamData data) {
        failIfInvalidArgument(data != null, "data must not be null");
        failIfInvalidArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s",
                data.family());

        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) data;
        failIfInvalidArgument(nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match NodeUpstream");

        try {
            stream.send(
                    new String(
                            codec.encode(data),
                            StandardCharsets.UTF_8));
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "Could not encode UpstreamData for node "
                            + nodeId.value(),
                    ex);
        }
    }

    /** Creates one focused task for one inbound semantic message. */
    private YieldingTask createMessageTask(
            UpstreamData data) {
        failIfInvalidArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s",
                data.family());

        UpstreamData.NodeScoped nodeScoped =
                (UpstreamData.NodeScoped) data;
        failIfInvalidArgument(nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                failIfInvalidArgument(data instanceof UpstreamData.RaceData,
                        "RACE_DATA has incompatible semantic type");
                return new RaceDataTask(
                        this,
                        raceDataSync,
                        nodeService,
                        (UpstreamData.RaceData) data);

            case REQ:
                failIfInvalidArgument(data instanceof UpstreamData.Req,
                        "REQ has incompatible semantic type");
                return new TimingDataReplayTask(
                        this,
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

    private UpstreamData readMessage() {
        String encoded = stream.read();
        try {
            return codec.decode(
                    encoded.getBytes(
                            StandardCharsets.UTF_8));
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalArgumentException(
                    "Invalid UpstreamData for node "
                            + nodeId.value(),
                    ex);
        }
    }

    /** Wraps one synchronous protocol operation as one ordinary task-run. */
    private YieldingTask oneStepTask(
            Runnable operation) {
        return () -> {
            if (isAvailable()) {
                operation.run();
            }
            return TaskStep.done();
        };
    }

    /** Starts exactly one message task; later input remains queued until it completes. */
    private void startMessageTask(
            YieldingTask task) {
        CompletableFuture<Void> run =
                taskRunner.runTask(task);
        synchronized (this) {
            if (activeMessageRun != null) {
                run.cancel(true);
                throw new IllegalStateException(
                        "NodeUpstream already has an active message task");
            }
            activeMessageRun = run;
        }
        run.whenComplete(
                (ignored, failure) ->
                        messageTaskCompleted(
                                run,
                                failure));
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
            timingDataSync.cancelReplay();
            raceDataSync.cancelApply();
            LOG.warn(
                    "Upstream message task failed for node {}",
                    nodeId.value(),
                    failure);
        }

        wakeQueuedInput();
    }

    private synchronized boolean messageTaskActive() {
        return activeMessageRun != null;
    }

    /** Wakes message admission while queue ownership remains in UpstreamStream. */
    private void wakeQueuedInput() {
        if (taskRunner.isRunning()
                && isAvailable()
                && stream.hasMessage()
                && !messageTaskActive()) {
            inputTask.wake(taskRunner);
        }
    }

    private synchronized void cancelActiveMessageTask() {
        CompletableFuture<Void> run =
                activeMessageRun;
        activeMessageRun = null;
        if (run != null) {
            run.cancel(true);
        }
    }

    private void onConnected(
            NodeId sourceNodeId) {
        requireOwnStream(sourceNodeId);
        if (taskRunner.isRunning()) {
            queueTimingDataSync();
            wakeQueuedInput();
        }
    }

    private void onDataAvailable(
            NodeId sourceNodeId) {
        requireOwnStream(sourceNodeId);
        wakeQueuedInput();
    }

    private void onHeartbeatTick(
            NodeId sourceNodeId) {
        requireOwnStream(sourceNodeId);
        if (taskRunner.isRunning()) {
            executeTaskOrWarn(
                    heartbeat::tick,
                    "heartbeat tick");
        }
    }

    private void requireOwnStream(
            NodeId sourceNodeId) {
        failIfInvalidArgument(nodeId.equals(sourceNodeId),
                "Upstream event belongs to node %s instead of %s",
                sourceNodeId == null ? "null" : sourceNodeId.value(),
                nodeId.value());
    }

    private void queueTimingDataSync() {
        executeTaskOrWarn(
                timingDataSync::sendSourceHead,
                "TIMING_DATA SYNC");
    }

    /** Executes one Task Lane operation and contains rejection at the event boundary. */
    private void executeTaskOrWarn(
            Runnable task,
            String operation) {
        Runnable queueTask =
                () ->
                        taskRunner.runTask(
                                () -> {
                                    task.run();
                                    return TaskStep.done();
                                });
        Consumer<RejectedExecutionException> onRejected =
                ex ->
                        LOG.warn(
                                "Dropped upstream {} for node {} because the Task Lane rejected execution",
                                operation,
                                nodeId.value(),
                                ex);

        SafeExecute.runOrHandleRejection(
                queueTask,
                onRejected);
    }

    /** Admits one queued encoded message as one focused semantic task. */
    private final class InputTask extends AbstractTask {
        @Override
        protected void resetForRun() {
            // No operation state: this task only admits one message.
        }

        @Override
        public TaskStep runStep() {
            if (!isAvailable()
                    || messageTaskActive()
                    || !stream.hasMessage()) {
                return TaskStep.done();
            }

            startMessageTask(
                    createMessageTask(
                            readMessage()));
            return TaskStep.done();
        }

        @Override
        protected void onRunCompleted(
                Throwable failure) {
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
