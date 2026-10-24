package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingSystem-owned semantic endpoint for one node's upstream communication.
 *
 * <p>Messaging delivers encoded packets through one node-scoped {@link UpstreamStream}.
 * This component owns packet identity, the {@link UpstreamDataCodec}, downstream delivery
 * state and TIMING_DATA/PING protocol behavior.</p>
 *
 * <p>Exactly one decoded inbound protocol operation is active per node. Later encoded
 * packets remain queued in the stream while an earlier yielding task awaits NodeService
 * or LogBook completion.</p>
 */
public final class NodeUpstream extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeUpstream.class);
    private static final int TASK_QUEUE_CAPACITY = 32;
    private static final long HEARTBEAT_INTERVAL_NANOS =
            TimeUnit.SECONDS.toNanos(30L);

    private final NodeId nodeId;
    private final ScheduledExecutorService scheduler;
    private final SerialTaskRunner taskRunner;
    private final UpstreamStream stream;
    private final UpstreamDataCodec codec;
    private final NodeService nodeService;
    private final Heartbeat heartbeat;
    private final DownstreamDelivery downstreamDelivery;
    private final TimingDataSync timingDataSync;
    private final TimingDataLiveTask timingDataLiveTask;
    private final UpstreamInputTask inputTask;

    private long nextUpPacketId = 1L;
    private ScheduledFuture<?> heartbeatSchedule;

    /** Creates one node's semantic upstream protocol state and independent logical task lane. */
    public NodeUpstream(
            NodeId nodeId,
            ScheduledExecutorService scheduler,
            UpstreamStream stream,
            UpstreamDataCodec codec,
            LogBookAccessor logBook,
            NodeService nodeService,
            TimeSource timeSource) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(codec != null, "codec must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");

        this.nodeId = nodeId;
        this.scheduler = scheduler;
        this.taskRunner =
                new SerialTaskRunner(
                        TASK_QUEUE_CAPACITY,
                        "Upstream-" + nodeId.value(),
                        scheduler);
        this.stream = stream;
        this.codec = codec;
        this.nodeService = nodeService;
        this.heartbeat =
                new Heartbeat(
                        this::isAvailable,
                        this::sendMessage,
                        timeSource);
        this.downstreamDelivery =
                new DownstreamDelivery(
                        this::isAvailable,
                        this::sendMessage);
        this.timingDataSync =
                new TimingDataSync(
                        this::isAvailable,
                        this::sendMessage,
                        logBook);
        this.timingDataLiveTask =
                new TimingDataLiveTask(
                        nodeId,
                        this::isAvailable,
                        timingDataSync);
        this.inputTask =
                new UpstreamInputTask(
                        nodeId,
                        stream,
                        this::isAvailable,
                        this::readPacket,
                        this::createPacketTask,
                        this::cancelPacketState);
    }

    /** Returns this node-specific protocol instance identity. */
    public NodeId nodeId() {
        return nodeId;
    }

    /** Returns the latest immutable heartbeat protocol status published by the task lane. */
    public NodeUpstreamTypes.HeartbeatStatus heartbeatStatus() {
        return heartbeat.status();
    }

    /** Starts serial protocol execution and its periodic heartbeat cadence. */
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
        timingDataLiveTask.cancel();
        timingDataSync.sessionUnavailable();
        downstreamDelivery.cancelPending();
        taskRunner.close();
    }

    /** Returns whether the node-scoped transport is currently available. */
    boolean isAvailable() {
        return stream.isAvailable();
    }

    /**
     * Wraps and sends one SI-01-to-Central semantic message.
     *
     * <p>PING is always packetId 0 and does not consume the normal positive packet
     * counter. Every other outbound message receives one new positive packet id for
     * traceability; current UP_PACKET delivery does not require RECV.</p>
     */
    void sendMessage(UpstreamData.Message message) {
        failIfInvalidArgument(message != null, "message must not be null");

        long packetId =
                message.family() == UpstreamData.Family.PING
                        ? 0L
                        : nextPositiveUpPacketId();

        sendPacket(
                DefaultUpstreamData.createUpPacket(
                        nodeId,
                        packetId,
                        message));
    }

    /** Encodes and sends one already composed outbound packet. */
    private void sendPacket(UpstreamData packet) {
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(
                packet.direction() == UpstreamData.Direction.UP_PACKET,
                "NodeUpstream sends UP_PACKET only");
        failIfInvalidArgument(
                nodeId.equals(packet.timingNodeId()),
                "UpstreamData NodeId does not match NodeUpstream");

        try {
            String encoded =
                    new String(
                            codec.encode(packet),
                            StandardCharsets.UTF_8);
            String summary = logSummary(packet);

            LOG.info("Upstream queued: {}", summary);
            stream.send(encoded);
            LOG.info("Upstream sent: {}", summary);
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "Could not encode UpstreamData for node "
                            + nodeId.value(),
                    ex);
        }
    }

    /** Allocates one positive UP_PACKET id without changing PING packet identity. */
    private long nextPositiveUpPacketId() {
        if (nextUpPacketId <= 0L) {
            throw new IllegalStateException(
                    "UP_PACKET id space exhausted for node "
                            + nodeId.value());
        }

        long result = nextUpPacketId;
        nextUpPacketId =
                result == Long.MAX_VALUE
                        ? 0L
                        : result + 1L;
        return result;
    }

    /** Builds one compact stable summary at the semantic packet boundary. */
    private static String logSummary(UpstreamData packet) {
        String prefix =
                "node=" + packet.timingNodeId().value()
                        + ", dir=" + packet.direction().name()
                        + ", packet=" + packet.packetId()
                        + ", type=" + packet.family().name();

        switch (packet.family()) {
            case TIMING_DATA:
                UpstreamData.TimingDataTransfer timingData =
                        (UpstreamData.TimingDataTransfer) packet.message();
                int recordCount = timingData.records().size();
                long firstSequence =
                        timingData.records().get(0).sequenceNumber();
                long lastSequence =
                        timingData.records()
                                .get(recordCount - 1)
                                .sequenceNumber();
                return prefix
                        + ", seq=" + firstSequence + ".." + lastSequence
                        + ", records=" + recordCount;

            case RACE_DATA:
                UpstreamData.RaceData raceData =
                        (UpstreamData.RaceData) packet.message();
                return prefix
                        + ", location=" + raceData.locationId().value()
                        + ", changes="
                        + (raceData.teamAssignmentChanges().size()
                                + raceData.stageStartTimeChanges().size());

            case TIMING_DATA_REQ:
                UpstreamData.TimingDataRequest request =
                        (UpstreamData.TimingDataRequest) packet.message();
                return prefix
                        + ", fromSeq=" + request.fromSequence();

            case SEQ:
                UpstreamData.Sequence sequence =
                        (UpstreamData.Sequence) packet.message();
                return prefix
                        + ", op=" + sequence.operation().name()
                        + ", seq="
                        + (sequence.sequence() == null
                                ? "-"
                                : sequence.sequence());

            case RECV:
                UpstreamData.Receive receive =
                        (UpstreamData.Receive) packet.message();
                return prefix
                        + ", receivedPacket="
                        + receive.receivedPacketId();

            case PING:
                UpstreamData.Ping ping =
                        (UpstreamData.Ping) packet.message();
                return prefix
                        + ", op=" + ping.operation().name()
                        + ", mode="
                        + (ping.mode() == null
                                ? "-"
                                : ping.mode().name())
                        + ", pingId="
                        + (ping.pingId() == null
                                ? "-"
                                : ping.pingId());

            default:
                return prefix;
        }
    }

    /**
     * Creates one focused task for one inbound packet.
     *
     * <p>Normal positive DN_PACKET messages all pass through DownstreamDelivery. PING
     * deliberately bypasses that state and therefore never produces RECV.</p>
     */
    private YieldingTask createPacketTask(UpstreamData packet) {
        failIfInvalidArgument(
                nodeId.equals(packet.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");
        failIfInvalidArgument(
                packet.direction() == UpstreamData.Direction.DN_PACKET,
                "NodeUpstream accepts DN_PACKET only");

        switch (packet.family()) {
            case RACE_DATA:
                return new RaceDataTask(
                        this::isAvailable,
                        downstreamDelivery,
                        nodeService,
                        packet,
                        (UpstreamData.RaceData) packet.message());

            case TIMING_DATA_REQ:
                return new TimingDataReplayTask(
                        this::isAvailable,
                        downstreamDelivery,
                        timingDataSync,
                        packet,
                        (UpstreamData.TimingDataRequest) packet.message(),
                        this::wakeLiveTimingData);

            case SEQ:
                return normalPacketTask(
                        packet,
                        () -> timingDataSync.handleSequence(
                                (UpstreamData.Sequence) packet.message()));

            case PING:
                return oneStepTask(
                        () -> heartbeat.handle(
                                (UpstreamData.Ping) packet.message()));

            default:
                throw new UnsupportedOperationException(
                        "Inbound UpstreamData family is not implemented by NodeUpstream: "
                                + packet.family());
        }
    }

    /** Decodes exactly one queued packet at the semantic boundary owned by NodeUpstream. */
    private UpstreamData readPacket() {
        String encoded = stream.read();
        try {
            UpstreamData packet =
                    codec.decode(
                            encoded.getBytes(StandardCharsets.UTF_8));
            LOG.info("Upstream received: {}", logSummary(packet));
            return packet;
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalArgumentException(
                    "Invalid UpstreamData for node "
                            + nodeId.value(),
                    ex);
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

    /** Wraps one synchronous normal DN_PACKET operation with generic RECV handling. */
    private YieldingTask normalPacketTask(
            UpstreamData packet,
            Runnable operation) {
        return () -> {
            if (!isAvailable()
                    || !downstreamDelivery.begin(packet)) {
                return TaskStep.done();
            }

            try {
                operation.run();
                downstreamDelivery.complete(packet.packetId());
            } catch (RuntimeException ex) {
                downstreamDelivery.cancelPending();
                throw ex;
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

    /** Clears packet-specific continuation state after one failed inbound operation. */
    private void cancelPacketState() {
        timingDataSync.cancelReplay();
        downstreamDelivery.cancelPending();
    }

    /**
     * Handles a connection fact from the stream paired with this NodeUpstream by TimingSystem.
     *
     * <p>A fresh connection starts a new TIMING_DATA position handoff. Downstream packet
     * position itself is retained across a transport reconnect of this runtime.</p>
     */
    public void connectionAvailable() {
        queueTimingDataSequenceNotification();
        queueHeartbeat();
        wakeQueuedInput();
    }

    /**
     * Handles the end of the transport session paired with this NodeUpstream by TimingSystem.
     *
     * <p>An already-running semantic apply is not cancelled merely because the socket disappeared.
     * Its packet position must still advance if the domain operation succeeds, so a resend after
     * reconnect cannot apply the same change twice.</p>
     */
    public void connectionUnavailable() {
        timingDataLiveTask.cancel();
        timingDataSync.sessionUnavailable();
    }

    /**
     * Handles a post-commit TimingData wake from the TimingNode paired by TimingSystem.
     *
     * <p>The event payload is deliberately not copied into an outbound queue. The live task reads
     * the authoritative LogBook by source sequence after the SEQ/TIMING_DATA_REQ handoff completes.</p>
     */
    public void timingDataChanged() {
        wakeLiveTimingData();
    }

    /** Handles a data-available fact from the stream paired with this NodeUpstream. */
    public void inputAvailable() {
        wakeQueuedInput();
    }

    /** Schedules periodic protocol heartbeat requests independently of transport implementation. */
    private void startHeartbeatSchedule() {
        heartbeatSchedule =
                scheduler.scheduleAtFixedRate(
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
        if (isAvailable()) {
            taskRunner.executeOrWarn(
                    heartbeat::tick,
                    "heartbeat tick");
        }
    }

    /** Wakes bounded live LogBook catch-up when the session and task lane can accept work. */
    private void wakeLiveTimingData() {
        if (taskRunner.isRunning() && isAvailable()) {
            timingDataLiveTask.wake(taskRunner);
        }
    }

    /** Queues the current TIMING_DATA position notification on this node's task lane. */
    private void queueTimingDataSequenceNotification() {
        taskRunner.executeOrWarn(
                timingDataSync::sendSourceHeadNotification,
                "TIMING_DATA SEQ NTF");
    }
}
