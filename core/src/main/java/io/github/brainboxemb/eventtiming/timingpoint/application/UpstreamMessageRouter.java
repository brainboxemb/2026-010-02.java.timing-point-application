package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.UpstreamMessagePort;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application boundary that resolves semantic UpstreamData to the owning
 * TimingNode without exposing transport adapters to Domain internals.
 *
 * <p>A02 implements ordered RACE_DATA ingress. Other semantic families are
 * deliberately rejected here until their Step-7 activity is implemented.</p>
 */
public final class UpstreamMessageRouter {

    public enum Outcome {
        APPLIED,
        DUPLICATE,
        GAP,
        REJECTED,
        UNKNOWN_NODE,
        UNSUPPORTED
    }

    public static final class Result {
        private final Outcome outcome;
        private final UpstreamData controlResponse;
        private final String message;

        private Result(
                Outcome outcome,
                UpstreamData controlResponse,
                String message) {
            this.outcome = outcome;
            this.controlResponse = controlResponse;
            this.message = message;
        }

        public Outcome outcome() {
            return outcome;
        }

        /**
         * Returns the semantic control response to send upstream, or null when
         * no ACK/REQ response is defined for this result.
         */
        public UpstreamData controlResponse() {
            return controlResponse;
        }

        public String message() {
            return message;
        }
    }

    private final Map<NodeId, UpstreamMessagePort> nodePorts;

    public UpstreamMessageRouter(
            TimingNodeList timingNodes) {
        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "timingNodes must contain at least one TimingNode");
        }

        Map<NodeId, UpstreamMessagePort> ports =
                new LinkedHashMap<NodeId, UpstreamMessagePort>();
        for (TimingNode timingNode : timingNodes) {
            NodeId nodeId = timingNode.timingNodeId();
            if (ports.put(
                    nodeId,
                    timingNode.upstreamMessagePort()) != null) {
                throw new IllegalArgumentException(
                        "Duplicate TimingNode id "
                                + nodeId.value());
            }
        }
        nodePorts = Collections.unmodifiableMap(ports);
    }

    public Result route(
            UpstreamData data) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null");
        }

        if (data.family() != UpstreamData.Family.RACE_DATA
                || !(data instanceof UpstreamData.RaceData)) {
            return new Result(
                    Outcome.UNSUPPORTED,
                    null,
                    "UpstreamData family is not implemented by A02: "
                            + data.family());
        }

        UpstreamData.RaceData raceData =
                (UpstreamData.RaceData) data;
        UpstreamMessagePort port =
                nodePorts.get(
                        raceData.timingNodeId());
        if (port == null) {
            return new Result(
                    Outcome.UNKNOWN_NODE,
                    null,
                    "Unknown TimingNode "
                            + raceData.timingNodeId().value());
        }

        TimingNodeTypes.RaceDataApplyResult apply =
                port.applyRaceData(
                        raceData);

        switch (apply.outcome()) {
            case APPLIED:
                return acknowledged(
                        Outcome.APPLIED,
                        raceData.timingNodeId(),
                        apply.acknowledgeSequence());
            case DUPLICATE:
                return acknowledged(
                        Outcome.DUPLICATE,
                        raceData.timingNodeId(),
                        apply.acknowledgeSequence());
            case GAP:
                return new Result(
                        Outcome.GAP,
                        DefaultUpstreamData.req(
                                raceData.timingNodeId(),
                                UpstreamData.Stream.RACE_DATA,
                                apply.requestedFromSequence()),
                        null);
            case REJECTED:
                return new Result(
                        Outcome.REJECTED,
                        null,
                        apply.message());
            default:
                throw new IllegalStateException(
                        "Unsupported RaceData outcome "
                                + apply.outcome());
        }
    }

    private static Result acknowledged(
            Outcome outcome,
            NodeId nodeId,
            long sequence) {
        return new Result(
                outcome,
                DefaultUpstreamData.ack(
                        nodeId,
                        UpstreamData.Stream.RACE_DATA,
                        sequence),
                null);
    }
}
