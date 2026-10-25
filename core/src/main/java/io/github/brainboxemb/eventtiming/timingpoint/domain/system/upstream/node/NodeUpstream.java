package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * TimingSystem-owned semantic endpoint for one node's upstream communication.
 *
 * <p>Messaging transports encoded packets only. This component owns IF-06 packet numbering,
 * semantic dispatch, downstream RECV state, TIMING_DATA synchronization and PING cadence.</p>
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
    private final DownstreamDelivery downstreamDelivery;
    private final TimingDataSync timingDataSync;
    private final TimingDataLiveTask timingDataLiveTask;
    private final UpstreamInputTask inputTask;
    private final ReadOnlyConfiguration<Duration> timingDataReplayPacketDelay;

    private ScheduledFuture<?> heartbeatSchedule;
    private long nextUpPacketNr = 1L;

    /** Creates one node's semantic upstream protocol state and independent logical task lane. */
    public NodeUpstream(
            NodeId nodeId,
            ScheduledExecutorService scheduler,
            UpstreamStream stream,
            UpstreamDataCodec codec,
            LogBookAccessor logBook,
            NodeService nodeService,
            TimeSource timeSource) {
        this(
                nodeId,
                scheduler,
                stream,
                codec,
                logBook,
                nodeService,
                timeSource,
                ReadOnlyConfiguration.fixed(
                        Duration.ZERO));
    }

    /** Creates one node's upstream protocol state with explicit fixed replay pacing. */
    public NodeUpstream(
            NodeId nodeId,
            ScheduledExecutorService scheduler,
            UpstreamStream stream,
            UpstreamDataCodec codec,
            LogBookAccessor logBook,
            NodeService nodeService,
            TimeSource timeSource,
            Duration timingDataReplayPacketDelay) {
        this(
                nodeId,
                scheduler,
                stream,
                codec,
                logBook,
                nodeService,
                timeSource,
                ReadOnlyConfiguration.fixed(
                        timingDataReplayPacketDelay));
    }

    /** Creates one node's upstream protocol state with live replay pacing configuration. */
    public NodeUpstream(
            NodeId nodeId,
            ScheduledExecutorService scheduler,
            UpstreamStream stream,
            UpstreamDataCodec codec,
            LogBookAccessor logBook,
            NodeService nodeService,
            TimeSource timeSource,
            ReadOnlyConfiguration<Duration> timingDataReplayPacketDelay) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(codec != null, "codec must not be null");
        failIfInvalidArgument(logBook != null, "logBook must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(
                timingDataReplayPacketDelay != null
                        && timingDataReplayPacketDelay.currentValue() != null
                        && !timingDataReplayPacketDelay.currentValue().isNegative(),
                "TIMING_DATA replay packet delay must not be negative");

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
        this.timingDataReplayPacketDelay =
                timingDataReplayPacketDelay;
        this.heartbeat =
                new Heartbeat(
                        nodeId,
                        this::isAvailable,
                        this::sendMessage,
                        timeSource);
        this.downstreamDelivery =
                new DownstreamDelivery(
                        this::isAvailable,
                        this::sendMessage);
        this.timingDataSync =
                new TimingDataSync(
                        nodeId,
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

    public NodeId nodeId() {
        return nodeId;
    }

    public NodeUpstreamTypes.HeartbeatStatus heartbeatStatus() {
        return heartbeat.status();
    }

    @Override
    protected void onActivate() {
        taskRunner.start();
        startHeartbeatSchedule();
        wakeQueuedInput();
    }

    @Override
    protected void onDeactivate() {
        cancelHeartbeatSchedule();
        inputTask.cancel();
        timingDataLiveTask.cancel();
        timingDataSync.sessionUnavailable();
        downstreamDelivery.cancelPending();
        taskRunner.close();
    }

    boolean isAvailable() {
        return stream.isAvailable();
    }

    /** Wraps one node-originated semantic message in its public IF-06 packet. */
    private void sendMessage(UpstreamData.Message message) {
        failIfInvalidArgument(message != null, "message must not be null");

        long packetNr;
        if (message.family() == UpstreamData.Family.PING) {
            packetNr = 0L;
        } else {
            if (nextUpPacketNr <= 0L) {
                throw new IllegalStateException("UP_PACKET packetNr exhausted");
            }
            packetNr = nextUpPacketNr++;
        }

        sendPacket(
                DefaultUpstreamData.createUpPacket(
                        nodeId,
                        packetNr,
                        message));
    }

    /** Encodes and sends one complete IF-06 packet. */
    private void sendPacket(UpstreamData packet) {
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(
                nodeId.equals(packet.timingNodeId()),
                "UpstreamData NodeId does not match NodeUpstream");

        try {
            String encoded =
                    new String(
                            codec.encode(packet),
                            StandardCharsets.UTF_8);

            String summary = NodeUpstreamLogging.summary(packet);

            LOG.info("Upstream queued: {}", summary);
            stream.send(encoded);
            LOG.info("Upstream sent: {}", summary);
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "Could not encode UpstreamData for node " + nodeId.value(),
                    ex);
        }
    }

    /** Creates one focused task for one inbound Central-to-node packet. */
    private YieldingTask createPacketTask(UpstreamData packet) {
        failIfInvalidArgument(
                nodeId.equals(packet.timingNodeId()),
                "UpstreamData NodeId does not match node-scoped stream");
        failIfInvalidArgument(
                packet.direction() == UpstreamData.Direction.DN_PACKET,
                "NodeUpstream accepts DN_PACKET only");

        if (packet.family() == UpstreamData.Family.PING) {
            return oneStepTask(
                    () -> heartbeat.handle(
                            (UpstreamData.Ping) packet.message()));
        }

        if (!downstreamDelivery.begin(packet)) {
            return oneStepTask(() -> { });
        }

        switch (packet.family()) {
            case RACE_DATA:
                return new RaceDataTask(
                        this::isAvailable,
                        downstreamDelivery,
                        nodeService,
                        packet);

            case TIMING_DATA_REQ:
                return new TimingDataReplayTask(
                        this::isAvailable,
                        timingDataSync,
                        (UpstreamData.TimingDataRequest) packet.message(),
                        () -> downstreamDelivery.complete(packet),
                        this::wakeLiveTimingData,
                        timingDataReplayPacketDelay.currentValue());

            case SEQ:
                return oneStepTask(
                        () -> {
                            timingDataSync.handleSequence(
                                    (UpstreamData.Sequence) packet.message());
                            downstreamDelivery.complete(packet);
                        });

            default:
                downstreamDelivery.cancelPending();
                throw new UnsupportedOperationException(
                        "Inbound IF-06 message is not implemented by NodeUpstream: "
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
            LOG.info(
                    "Upstream received: {}",
                    NodeUpstreamLogging.summary(packet));
            return packet;
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalArgumentException(
                    "Invalid UpstreamData for node " + nodeId.value(),
                    ex);
        }
    }

    private YieldingTask oneStepTask(Runnable operation) {
        return () -> {
            if (isAvailable()) {
                operation.run();
            }
            return TaskStep.done();
        };
    }

    private void wakeQueuedInput() {
        if (isAvailable() && stream.hasMessage()) {
            inputTask.wake(taskRunner);
        }
    }

    /** Clears continuation state after one failed inbound packet operation. */
    private void cancelPacketState() {
        timingDataSync.cancelReplay();
        downstreamDelivery.cancelPending();
    }

    /** Handles a connection fact from this node's fixed upstream stream. */
    public void connectionAvailable() {
        queueDownstreamReady();
        queueTimingDataSequenceNotification();
        queueHeartbeat();
        wakeQueuedInput();
    }

    /** Handles transport-session loss without discarding committed protocol positions. */
    public void connectionUnavailable() {
        inputTask.cancel();
        timingDataLiveTask.cancel();
        timingDataSync.sessionUnavailable();
        downstreamDelivery.cancelPending();
    }

    /** Handles a post-commit TimingData wake; the LogBook remains the source. */
    public void timingDataChanged() {
        if (taskRunner.isRunning()
                && isAvailable()
                && timingDataSync.liveReady()) {
            wakeLiveTimingData();
        }
    }

    /** Handles a data-available fact from the node-scoped stream. */
    public void inputAvailable() {
        wakeQueuedInput();
    }

    private void startHeartbeatSchedule() {
        heartbeatSchedule =
                scheduler.scheduleAtFixedRate(
                        this::queueHeartbeat,
                        HEARTBEAT_INTERVAL_NANOS,
                        HEARTBEAT_INTERVAL_NANOS,
                        TimeUnit.NANOSECONDS);
    }

    private void cancelHeartbeatSchedule() {
        ScheduledFuture<?> current = heartbeatSchedule;
        heartbeatSchedule = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    private void queueHeartbeat() {
        if (isAvailable()) {
            taskRunner.executeOrWarn(
                    heartbeat::tick,
                    "heartbeat tick");
        }
    }

    private void wakeLiveTimingData() {
        if (taskRunner.isRunning() && isAvailable()) {
            timingDataLiveTask.wake(taskRunner);
        }
    }

    /** Queues the current TIMING_DATA source-head notification on this node's task lane. */
    private void queueTimingDataSequenceNotification() {
        taskRunner.executeOrWarn(
                timingDataSync::sendSourceHeadNotification,
                "TIMING_DATA SEQ NTF");
    }

    /** Announces readiness for a full current downstream state on a fresh delivery state. */
    private void queueDownstreamReady() {
        taskRunner.executeOrWarn(
                downstreamDelivery::requestFullStateIfUninitialized,
                "downstream RECV 0");
    }
}
