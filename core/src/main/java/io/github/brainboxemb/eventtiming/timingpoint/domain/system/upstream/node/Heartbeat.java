package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns heartbeat protocol state for one NodeUpstream.
 *
 * <p>This helper has no worker or timer. Runtime scheduling decides when a
 * heartbeat tick occurs; the owning NodeUpstream task lane calls {@link #tick()}
 * and {@link #handle(UpstreamData.Ping)}. Mutable heartbeat protocol state
 * therefore has one serial owner even though its immutable status snapshot may
 * be read directly.</p>
 */
final class Heartbeat {
    private final NodeId nodeId;
    private final UpstreamStream stream;

    private long requestsSent;
    private long answersReceived;
    private long missedIntervals;
    private boolean answerPending;
    private String pendingPingId;

    /** Latest immutable NodeUpstream lane-owned heartbeat state for direct reads. */
    private volatile NodeUpstreamTypes.HeartbeatStatus publishedStatus =
            new NodeUpstreamTypes.HeartbeatStatus(
                    0L,
                    0L,
                    0L,
                    false);

    /** Creates heartbeat protocol state for one node-scoped upstream stream. */
    Heartbeat(NodeId nodeId, UpstreamStream stream) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
    }

    /**
     * Sends one heartbeat request when communication is available.
     *
     * <p>An unanswered previous request counts as one missed interval before the
     * next request replaces its ping id.</p>
     */
    void tick() {
        if (!stream.isAvailable()) {
            return;
        }

        if (answerPending) {
            missedIntervals++;
        }

        requestsSent++;
        answerPending = true;
        pendingPingId = "hb-" + requestsSent;

        publishStatus();
        stream.send(DefaultUpstreamData.ping(
                nodeId,
                UpstreamData.PingOperation.REQ,
                UpstreamData.PingMode.HB,
                pendingPingId));
    }

    /**
     * Handles both peer PING requests and answers to our heartbeat request.
     */
    void handle(UpstreamData.Ping ping) {
        failIfInvalidArgument(ping != null, "ping must not be null");

        if (ping.operation() == UpstreamData.PingOperation.REQ) {
            stream.send(DefaultUpstreamData.ping(
                    nodeId,
                    UpstreamData.PingOperation.ANS,
                    ping.mode(),
                    ping.pingId()));
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

    /** Returns the latest immutable heartbeat status published by the NodeUpstream lane. */
    NodeUpstreamTypes.HeartbeatStatus status() {
        return publishedStatus;
    }

    /** Publishes the current mutable heartbeat counters as one immutable direct-read snapshot. */
    private void publishStatus() {
        publishedStatus =
                new NodeUpstreamTypes.HeartbeatStatus(
                        requestsSent,
                        answersReceived,
                        missedIntervals,
                        answerPending);
    }
}
