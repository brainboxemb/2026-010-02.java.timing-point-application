package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private final UpstreamInputTask inputTask;
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
        this.inputTask = new UpstreamInputTask(nodeId, stream, this::isAvailable,
                this::readMessage, this::createMessageTask, taskRunner,
                this::onMessageFailure);
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
                nodeId.equals(data.timingNodeId()),
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
        failIfInvalidArgument(
                nodeId.equals(data.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");

        switch (data.family()) {
            case RACE_DATA:
                return new RaceDataTask(
                        this::isAvailable,
                        raceDataSync,
                        nodeService,
                        (UpstreamData.RaceData) data);

            case REQ:
                return new TimingDataReplayTask(
                        this::isAvailable,
                        timingDataSync,
                        (UpstreamData.Req) data);

            case SYNC:
                return oneStepTask(() -> raceDataSync.handleSync((UpstreamData.Sync) data));

            case PING:
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

    /** Wakes the task that owns stream consumption and message continuation. */
    private void wakeQueuedInput() {
        if (isAvailable() && stream.hasMessage()) {
            inputTask.wake(taskRunner);
        }
    }

    /** Clears interrupted replay/apply state after one failed inbound operation. */
    private void onMessageFailure(Throwable failure) {
        timingDataSync.cancelReplay();
        raceDataSync.cancelApply();
    }

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
            taskRunner.executeOrWarn(heartbeat::tick, "heartbeat tick for node " + nodeId.value());
        }
    }

    /** Queues the current TIMING_DATA source-head advertisement on this node's task lane. */
    private void queueTimingDataSync() {
        taskRunner.executeOrWarn(timingDataSync::sendSourceHead, "TIMING_DATA SYNC for node " + nodeId.value());
    }

}
