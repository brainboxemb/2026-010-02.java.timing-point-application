package oss.relay.eventtiming.timingpoint.domain.system.upstream.node;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingdata.TimingTimestamp;
import oss.relay.eventtiming.timingpoint.platform.time.TimeSource;
import oss.relay.eventtiming.upstreamdata.UpstreamData;
import oss.relay.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Owns heartbeat protocol state for one NodeUpstream. */
final class Heartbeat {
    private final BooleanSupplier available;
    private final Consumer<UpstreamData.Message> sender;
    private final TimeSource timeSource;

    private long requestsSent;
    private long answersReceived;
    private long missedIntervals;
    private boolean answerPending;
    private String pendingPingId;

    private volatile NodeUpstreamTypes.HeartbeatStatus publishedStatus =
            new NodeUpstreamTypes.HeartbeatStatus(
                    0L,
                    0L,
                    0L,
                    false);

    Heartbeat(
            NodeId nodeId,
            BooleanSupplier available,
            Consumer<UpstreamData.Message> sender,
            TimeSource timeSource) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(sender != null, "sender must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        this.available = available;
        this.sender = sender;
        this.timeSource = timeSource;
    }

    /** Sends one heartbeat request when communication is available. */
    void tick() {
        if (!available.getAsBoolean()) {
            return;
        }

        if (answerPending) {
            missedIntervals++;
        }

        requestsSent++;
        answerPending = true;
        pendingPingId = "hb-" + requestsSent;

        publishStatus();
        sender.accept(
                DefaultUpstreamData.createPing(
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        pendingPingId,
                        messageTime()));
    }

    /** Handles peer PING requests and answers to this node's heartbeat request. */
    void handle(UpstreamData.Ping ping) {
        failIfInvalidArgument(ping != null, "ping must not be null");

        if (ping.operation() == UpstreamData.PingOperation.REQ) {
            sender.accept(
                    DefaultUpstreamData.createPing(
                            UpstreamData.PingOperation.ANS,
                            ping.mode(),
                            ping.pingId(),
                            messageTime()));
        } else if (ping.operation() == UpstreamData.PingOperation.ANS
                && ping.mode() == UpstreamData.PingMode.HB
                && answerPending
                && pendingPingId != null
                && pendingPingId.equals(ping.pingId())) {
            answerPending = false;
            answersReceived++;
            publishStatus();
        }
    }

    private TimingTimestamp messageTime() {
        return new TimingTimestamp(timeSource.now());
    }

    NodeUpstreamTypes.HeartbeatStatus status() {
        return publishedStatus;
    }

    private void publishStatus() {
        publishedStatus =
                new NodeUpstreamTypes.HeartbeatStatus(
                        requestsSent,
                        answersReceived,
                        missedIntervals,
                        answerPending);
    }
}
