package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

/**
 * Public value types exposed by the TimingNode upstream boundary.
 *
 * <p>This class is only a Java source-code grouping. It has no runtime state,
 * worker or protocol responsibility of its own.</p>
 */
public final class UpstreamMessageTypes {
    private UpstreamMessageTypes() {
    }

    /**
     * Immutable diagnostic snapshot of one TimingNode heartbeat.
     */
    public static final class HeartbeatStatus {
        private final long requestsSent;
        private final long answersReceived;
        private final long missedIntervals;
        private final boolean answerPending;

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

        public long requestsSent() {
            return requestsSent;
        }

        public long answersReceived() {
            return answersReceived;
        }

        public long missedIntervals() {
            return missedIntervals;
        }

        public boolean answerPending() {
            return answerPending;
        }
    }
}
