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
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingSystem-owned semantic endpoint for one node's upstream communication.
 *
 * <p>Messaging delivers encoded messages through one node-scoped {@link UpstreamStream}. This component owns the
 * {@link UpstreamDataCodec}, so semantic IF-06 types do not leak into gateway or connector code.</p>
 *
 * <p>Exactly one decoded inbound protocol operation is active per node. Later encoded messages remain queued in the
 * stream while an earlier yielding task awaits NodeService or LogBook completion.</p>
 *
 * <p>Heartbeat cadence is protocol behavior and is therefore scheduled here on the same Runtime-owned scheduler used
 * by this node's task runner. The transport connector only reports connection state; it does not know about PING.</p>
 */
public final class NodeUpstream extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeUpstream.class);
    private static final int TASK_QUEUE_CAPACITY = 32;
    private static final long HEARTBEAT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30L);

    private final NodeId nodeId;
    private final ScheduledExecutorService scheduler;
    private final SerialTaskRunner taskRunner;
    private final UpstreamStream stream;
    private final UpstreamDataCodec codec;
    private final NodeService nodeService;
    private final Heartbeat heartbeat;
    private final RaceDataSync raceDataSync;
    private final TimingDataSync timingDataSync;
    private final InputTask inputTask = new InputTask();

    private CompletableFuture<Void> activeMessageRun;
    private ScheduledFuture<?> heartbeatSchedule;

    /** Creates one node's semantic upstream protocol state and independent logical task lane. */
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
        this.scheduler = scheduler;
        this.taskRunner = new SerialTaskRunner(TASK_QUEUE_CAPACITY, "Upstream-" + nodeId.value(), scheduler);
        this.stream = stream;
        this.codec = codec;
        this.nodeService = nodeService;
        this.heartbeat = new Heartbeat(nodeId, this::isAvailable, this::send);
        this.raceDataSync = new RaceDataSync(nodeId, this::isAvailable, this::send);
        this.timingDataSync = new TimingDataSync(nodeId, this::isAvailable, this::send, logBook);
    }

    /** Returns this node-specific protocol instance identity. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Returns the latest immutable heartbeat protocol status published by the task lane. */
    public NodeUpstreamTypes.HeartbeatStatus heartbeatStatus() {
        return heartbeat.status();
    }

    /**
     * Starts serial protocol execution and its periodic heartbeat cadence.
     *
     * <p>The first periodic tick is delayed by one full interval. A newly connected stream requests an immediate
     * heartbeat through {@link #connectionAvailable()}.</p>
     */
    @Override
    protected void onActivate() {
        taskRunner.start();
        startHeartbeatSchedule();
        wakeQueuedInput();
    }

    /** Cancels protocol work, heartbeat cadence and this node's logical task lane. */
    @Override
    protected void onDeactivate() {
        cancelHeartbeatSchedule();
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

    /**
     * Encodes and sends one semantic IF-06 message.
     *
     * <p>NodeUpstream validates node ownership before the encoded value crosses back into Messaging. The stream then
     * uses its fixed application-port binding and does not route through the gateway by NodeId again.</p>
     */
    void send(UpstreamData data) {
        failIfInvalidArgument(data != null, "data must not be null");
        failIfInvalidArgument(
                data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s", data.family());

        UpstreamData.NodeScoped nodeScoped = (UpstreamData.NodeScoped) data;
        failIfInvalidArgument(
                nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match NodeUpstream");

        try {
            stream.send(new String(codec.encode(data), StandardCharsets.UTF_8));
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException("Could not encode UpstreamData for node " + nodeId.value(), ex);
        }
    }

    /**
     * Creates one focused task for one inbound semantic message.
     *
     * <p>Message order is preserved by admitting the next stream value only after the returned task completes.</p>
     */
    private YieldingTask createMessageTask(UpstreamData data) {
        failIfInvalidArgument(data instanceof UpstreamData.NodeScoped,
                "UpstreamData is not node-scoped: %s", data.family());

        UpstreamData.NodeScoped nodeScoped = (UpstreamData.NodeScoped) data;
        failIfInvalidArgument(
                nodeId.equals(nodeScoped.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                failIfInvalidArgument(
                        data instanceof UpstreamData.RaceData,
                        "RACE_DATA has incompatible semantic type");
                return new RaceDataTask(this::isAvailable, raceDataSync, nodeService, (UpstreamData.RaceData) data);

            case REQ:
                failIfInvalidArgument(data instanceof UpstreamData.Req, "REQ has incompatible semantic type");
                return new TimingDataReplayTask(this::isAvailable, timingDataSync, (UpstreamData.Req) data);

            case SYNC:
                failIfInvalidArgument(data instanceof UpstreamData.Sync, "SYNC has incompatible semantic type");
                return oneStepTask(() -> raceDataSync.handleSync((UpstreamData.Sync) data));

            case PING:
                failIfInvalidArgument(data instanceof UpstreamData.Ping, "PING has incompatible semantic type");
                return oneStepTask(() -> heartbeat.handle((UpstreamData.Ping) data));

            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by NodeUpstream: " + data.family());
        }
    }

    /** Decodes exactly one queued stream value at the semantic boundary owned by NodeUpstream. */
    private UpstreamData readMessage() {
        String encoded = stream.read();
        try {
            return codec.decode(encoded.getBytes(StandardCharsets.UTF_8));
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalArgumentException("Invalid UpstreamData for node " + nodeId.value(), ex);
        }
    }

    /** Wraps one synchronous protocol operation as one ordinary task-run. */
    private YieldingTask oneStepTask(Runnable operation) {
        return () -> {
            if (isAvailable()) {
                operation.run();
            }
            return TaskStep.done();
        };
    }

    /** Starts exactly one semantic message task; later input remains queued until it completes. */
    private void startMessageTask(YieldingTask task) {
        CompletableFuture<Void> run = taskRunner.runTask(task);
        synchronized (this) {
            if (activeMessageRun != null) {
                run.cancel(true);
                throw new IllegalStateException("NodeUpstream already has an active message task");
            }
            activeMessageRun = run;
        }
        run.whenComplete((ignored, failure) -> messageTaskCompleted(run, failure));
    }

    /** Clears one completed message task and then admits queued follow-up input. */
    private void messageTaskCompleted(CompletableFuture<Void> run, Throwable failure) {
        synchronized (this) {
            if (activeMessageRun != run) {
                return;
            }
            activeMessageRun = null;
        }

        if (failure != null) {
            timingDataSync.cancelReplay();
            raceDataSync.cancelApply();
            LOG.warn("Upstream message task failed for node {}", nodeId.value(), failure);
        }

        wakeQueuedInput();
    }

    /** Reports whether one semantic stream message is still executing or awaiting completion. */
    private synchronized boolean messageTaskActive() {
        return activeMessageRun != null;
    }

    /**
     * Wakes message admission without moving queue ownership out of UpstreamStream.
     *
     * <p>Repeated DATA_AVAILABLE events may coalesce because the authoritative condition
     * is the stream queue itself.</p>
     */
    private void wakeQueuedInput() {
        if (taskRunner.isRunning() && isAvailable() && stream.hasMessage() && !messageTaskActive()) {
            inputTask.wake(taskRunner);
        }
    }

    /** Cancels the active semantic message continuation without consuming later queued stream input. */
    private synchronized void cancelActiveMessageTask() {
        CompletableFuture<Void> run = activeMessageRun;
        activeMessageRun = null;
        if (run != null) {
            run.cancel(true);
        }
    }

    /**
     * Handles a new transport session for this already-bound stream.
     *
     * <p>The stream object itself is the event subject; NodeId is not repeated in the event payload. Reconnection
     * publishes the current TIMING_DATA head and starts a fresh PING exchange immediately.</p>
     */
    /**
     * Handles a connection fact from the stream paired with this NodeUpstream by TimingSystem.
     *
     * <p>The source identity is deliberately not part of this API. TimingSystem owns the fixed
     * stream-to-node binding, so this component only receives the semantic fact that its upstream
     * connection became available.</p>
     */
    public void connectionAvailable() {
        if (!taskRunner.isRunning()) {
            return;
        }

        queueTimingDataSync();
        queueHeartbeat();
        wakeQueuedInput();
    }

    /**
     * Handles a data-available fact from the stream paired with this NodeUpstream by TimingSystem.
     *
     * <p>The stream already owns the queued encoded data. This input only wakes semantic admission.</p>
     */
    public void inputAvailable() {
        wakeQueuedInput();
    }

    /**
     * Schedules periodic protocol heartbeat requests independently of any concrete transport implementation.
     *
     * <p>The scheduler callback only admits a short task-lane operation. Heartbeat mutable state therefore remains
     * serialized with the other NodeUpstream protocol state.</p>
     */
    private void startHeartbeatSchedule() {
        heartbeatSchedule = scheduler.scheduleAtFixedRate(
                this::queueHeartbeat,
                HEARTBEAT_INTERVAL_NANOS,
                HEARTBEAT_INTERVAL_NANOS,
                TimeUnit.NANOSECONDS);
    }

    /** Stops periodic heartbeat admission before the task lane itself is closed. */
    private void cancelHeartbeatSchedule() {
        ScheduledFuture<?> current = heartbeatSchedule;
        heartbeatSchedule = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    /** Queues one heartbeat protocol turn only while this component and transport are operational. */
    private void queueHeartbeat() {
        if (taskRunner.isRunning() && isAvailable()) {
            executeTaskOrWarn(heartbeat::tick, "heartbeat tick");
        }
    }

    /** Queues the current TIMING_DATA source-head advertisement on this node's task lane. */
    private void queueTimingDataSync() {
        executeTaskOrWarn(timingDataSync::sendSourceHead, "TIMING_DATA SYNC");
    }

    /**
     * Executes one small protocol operation and contains task-lane rejection at the event/timer boundary.
     *
     * <p>The helper does not hide semantic work: callers name the concrete protocol operation they admit.</p>
     */
    private void executeTaskOrWarn(Runnable task, String operation) {
        Runnable queueTask = () -> taskRunner.runTask(() -> {
            task.run();
            return TaskStep.done();
        });
        Consumer<RejectedExecutionException> onRejected = ex ->
                LOG.warn("Dropped upstream {} for node {} because the Task Lane rejected execution",
                        operation, nodeId.value(), ex);

        SafeExecute.runOrHandleRejection(queueTask, onRejected);
    }

    /**
     * Admits one queued encoded message as one focused semantic task.
     *
     * <p>This task never owns protocol state itself; it only bridges the stream queue to the single active semantic
     * message continuation.</p>
     */
    private final class InputTask extends AbstractTask {
        @Override
        protected void resetForRun() {
            // No operation state: this task only admits one message.
        }

        @Override
        public TaskStep runStep() {
            if (!isAvailable() || messageTaskActive() || !stream.hasMessage()) {
                return TaskStep.done();
            }

            startMessageTask(createMessageTask(readMessage()));
            return TaskStep.done();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            if (failure != null) {
                LOG.warn("Upstream input admission failed for node {}", nodeId.value(), failure);
                wakeQueuedInput();
            }
        }
    }
}
