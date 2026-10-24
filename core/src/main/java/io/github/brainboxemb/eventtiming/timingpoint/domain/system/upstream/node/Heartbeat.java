package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns heartbeat protocol state for one NodeUpstream.
 *
 * <p>This helper owns semantic PING creation/response only. NodeUpstream wraps every PING
 * in packetId 0 and keeps heartbeat cadence serialized with the other protocol state.</p>
 */
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
            BooleanSupplier available,
            Consumer<UpstreamData.Message> sender,
            TimeSource timeSource) {
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
    void handle(
            UpstreamData.Ping ping) {
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

    /** Samples the composed timing source once for one newly created PING message. */
    private TimingTimestamp messageTime() {
        return new TimingTimestamp(timeSource.now());
    }

    /** Returns the latest immutable heartbeat state published by the serial owner. */
    NodeUpstreamTypes.HeartbeatStatus status() {
        return publishedStatus;
    }

    /** Publishes the current counters as one immutable direct-read snapshot. */
    private void publishStatus() {
        publishedStatus =
                new NodeUpstreamTypes.HeartbeatStatus(
                        requestsSent,
                        answersReceived,
                        missedIntervals,
                        answerPending);
    }
}
