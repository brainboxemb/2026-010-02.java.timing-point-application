package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandlerTypes;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * TimingSystem-owned upstream protocol component.
 *
 * <p>One node-scoped UpstreamHandler is retained for every TimingNode. Each
 * handler therefore owns independent TIMING_DATA, RACE_DATA and heartbeat
 * protocol state. The physical connection and queue implementation belongs to
 * the shared UpstreamGateway.</p>
 *
 * <p>The handlers deliberately continue to execute on their corresponding
 * TimingNode Task Lanes. This preserves the existing single-lane ordering of
 * RACE_DATA application and node state while moving protocol ownership to the
 * TimingSystem. No additional Java thread, executor or scheduler is created.</p>
 */
public final class Upstream extends LifecycleComponent {
    private final UpstreamGateway gateway;
    private final Map<NodeId, UpstreamHandler> nodes =
            new LinkedHashMap<NodeId, UpstreamHandler>();

    public Upstream(
            UpstreamGateway gateway,
            Iterable<UpstreamHandler> handlers) {

        failIfInvalidArgument(gateway != null, "gateway must not be null");
        failIfInvalidArgument(handlers != null, "handlers must not be null");
        this.gateway = gateway;

        for (UpstreamHandler handler : handlers) {
            failIfInvalidArgument(
                    handler != null,
                    "handlers must not contain null");
            UpstreamHandler previous =
                    nodes.put(
                            handler.nodeId(),
                            handler);
            failIfInvalidArgument(
                    previous == null,
                    "duplicate upstream node %s",
                    handler.nodeId().value());
        }
    }

    /** Returns the number of node-specific upstream protocol instances. */
    public int nodeCount() {
        return nodes.size();
    }

    /**
     * Signals that this node's gateway input queue contains data.
     *
     * <p>The gateway callback performs no semantic work; processing is admitted
     * to the node's existing Task Lane.</p>
     */
    public boolean inputAvailable(NodeId nodeId) {

        failIfInactive();
        return node(nodeId).inputAvailable();
    }

    /** Publishes every node's current TIMING_DATA source head. */
    public void sendTimingDataSync() {

        failIfInactive();
        publishTimingDataHeads();
    }

    /** Runs one externally scheduled heartbeat tick for every node. */
    public void heartbeatTick() {

        failIfInactive();
        for (UpstreamHandler handler : nodes.values()) {
            handler.heartbeatTick();
        }
    }

    /** Returns one node's latest heartbeat snapshot. */
    public UpstreamHandlerTypes.HeartbeatStatus heartbeatStatus(
            NodeId nodeId) {

        failIfInactive();
        return node(nodeId).heartbeatStatus();
    }

    /**
     * Initial transport reconciliation.
     *
     * <p>If the gateway is already connected when TimingSystem activation
     * reaches Upstream, publish the node source heads. Reconnect notification
     * can call sendTimingDataSync again without changing protocol state.</p>
     */
    @Override
    protected void onActivate() {

        if (gateway.isAvailable()) {
            publishTimingDataHeads();
        }
    }

    @Override
    protected void onDeactivate() {
        // Gateway owns connection shutdown. TimingNode lifecycle stops the
        // Task Lanes after Upstream has stopped accepting system callbacks.
    }

    private void publishTimingDataHeads() {

        for (UpstreamHandler handler : nodes.values()) {
            handler.sendTimingDataSync();
        }
    }

    private UpstreamHandler node(NodeId nodeId) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        UpstreamHandler handler = nodes.get(nodeId);
        failIfIllegalState(
                handler != null,
                "No upstream protocol state exists for node %s",
                nodeId.value());
        return handler;
    }
}
