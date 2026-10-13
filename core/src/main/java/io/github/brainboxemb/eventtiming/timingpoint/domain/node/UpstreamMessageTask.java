package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

/**
 * One upstream turn on the owning TimingNode serial lane.
 *
 * <p>The task carries no message. It reads exactly one message from the
 * node-scoped UpstreamStream and lets UpstreamMessagePort process it. When more
 * input is queued, the port schedules another task at the back of the same
 * lane so registrations and other node work get a turn in between.</p>
 */
final class UpstreamMessageTask implements Runnable {
    private final UpstreamMessagePort port;

    UpstreamMessageTask(
            UpstreamMessagePort port) {
        if (port == null) {
            throw new IllegalArgumentException(
                    "port must not be null");
        }
        this.port = port;
    }

    @Override
    public void run() {
        port.processNextOnNodeLane();
    }
}
