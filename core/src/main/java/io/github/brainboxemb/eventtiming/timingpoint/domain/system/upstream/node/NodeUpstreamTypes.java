package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

/**
 * Immutable diagnostic value types for one node's upstream protocol.
 *
 * <p>This class is only a Java source-code grouping. It has no runtime state,
 * worker or protocol responsibility of its own.</p>
 */
public final class NodeUpstreamTypes {
    /** Prevents instantiation of this source-code grouping class. */
    private NodeUpstreamTypes() {
    }

    /**
     * Immutable diagnostic snapshot of one TimingNode heartbeat.
     */
    public static final class HeartbeatStatus {
        private final long requestsSent;
        private final long answersReceived;
        private final long missedIntervals;
        private final boolean answerPending;

        /** Creates one immutable heartbeat diagnostic snapshot. */
        HeartbeatStatus(
                long requestsSent,
                long answersReceived,
                long missedIntervals,
                boolean answerPending) {
            this.requestsSent = requestsSent;
            this.answersReceived = answersReceived;
            this.missedIntervals = missedIntervals;
            this.answerPending = answerPending;
        }

        /** Returns the number of heartbeat requests sent by this node. */
        public long requestsSent() {
            return requestsSent;
        }

        /** Returns matching heartbeat answers received by this node. */
        public long answersReceived() {
            return answersReceived;
        }

        /** Returns intervals in which the previous heartbeat remained unanswered. */
        public long missedIntervals() {
            return missedIntervals;
        }

        /** Reports whether the latest heartbeat request still awaits its matching answer. */
        public boolean answerPending() {
            return answerPending;
        }
    }
}
