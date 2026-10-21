package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Owns heartbeat protocol state for one NodeUpstream. */
final class Heartbeat {
    private final NodeId nodeId;
    private final NodeUpstream upstream;

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
            NodeUpstream upstream) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(upstream != null, "upstream must not be null");
        this.nodeId = nodeId;
        this.upstream = upstream;
    }

    void tick() {
        if (!upstream.isAvailable()) {
            return;
        }

        if (answerPending) {
            missedIntervals++;
        }

        requestsSent++;
        answerPending = true;
        pendingPingId = "hb-" + requestsSent;

        publishStatus();
        upstream.send(
                DefaultUpstreamData.ping(
                        nodeId,
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        pendingPingId));
    }

    void handle(
            UpstreamData.Ping ping) {
        failIfInvalidArgument(ping != null, "ping must not be null");

        if (ping.operation() == UpstreamData.PingOperation.REQ) {
            upstream.send(
                    DefaultUpstreamData.ping(
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
