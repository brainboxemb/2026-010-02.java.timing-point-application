package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Routes inbound connector data to the fixed node-scoped UpstreamStreams composed for one system.
 *
 * <p>The routing table is complete at construction. Runtime operation cannot create, replace or
 * rebind application ports.</p>
 */
public final class UpstreamGateway {

    public enum ReceiveResult {
        ACCEPTED,
        UNKNOWN_NODE,
        INPUT_FULL
    }

    private final UpstreamConnector connector;
    private final Map<NodeId, UpstreamStream> streams;
    private final List<UpstreamStream> streamList;

    /** All stream bindings must already be complete and must use the supplied connector. */
    public UpstreamGateway(UpstreamConnector connector, Map<NodeId, UpstreamStream> nodeStreams) {

        failIfInvalidArgument(connector != null, "connector must not be null");
        failIfInvalidArgument(nodeStreams != null, "nodeStreams must not be null");

        Map<NodeId, UpstreamStream> copy = new LinkedHashMap<NodeId, UpstreamStream>();
        for (Map.Entry<NodeId, UpstreamStream> entry : nodeStreams.entrySet()) {
            NodeId nodeId = entry.getKey();
            UpstreamStream stream = entry.getValue();

            failIfInvalidArgument(nodeId != null, "nodeStreams must not contain a null NodeId");
            failIfInvalidArgument(stream != null, "nodeStreams must not contain a null stream");
            failIfInvalidArgument(stream.isBoundTo(nodeId, connector),
                    "UpstreamStream binding does not match node %s and connector", nodeId.value());

            copy.put(nodeId, stream);
        }

        this.connector = connector;
        this.streams = Collections.unmodifiableMap(copy);
        this.streamList = Collections.unmodifiableList(new ArrayList<UpstreamStream>(copy.values()));

        connector.bindInput(this::receive);
        connector.CONNECTED.subscribe(this::onConnected);
        connector.DISCONNECTED.subscribe(this::onDisconnected);
    }

    /** Unknown NodeIds are rejected without changing any stream queue. */
    private ReceiveResult receive(NodeId nodeId, String encodedMessage) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");

        UpstreamStream stream = streams.get(nodeId);
        if (stream == null) {
            return ReceiveResult.UNKNOWN_NODE;
        }

        return stream.offer(encodedMessage) ? ReceiveResult.ACCEPTED : ReceiveResult.INPUT_FULL;
    }

    /** The subscribed connector is fixed, so one connection fact applies to every composed stream. */
    private void onConnected(UpstreamConnector source) {

        failIfInvalidArgument(source == connector, "Connection event came from an unexpected connector");
        for (UpstreamStream stream : streamList) {
            stream.connected();
        }
    }

    /** Session input is invalid after disconnect and is cleared from every composed stream. */
    private void onDisconnected(UpstreamConnector source) {

        failIfInvalidArgument(source == connector, "Disconnect event came from an unexpected connector");
        for (UpstreamStream stream : streamList) {
            stream.disconnected();
        }
    }
}
