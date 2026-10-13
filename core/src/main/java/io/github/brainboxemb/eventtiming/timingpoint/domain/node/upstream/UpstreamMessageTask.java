package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

/**
 * One upstream data/replay turn on the owning TimingNode Data Lane.
 *
 * <p>The task carries no message. One turn either handles one queued inbound
 * semantic message or sends one LogBook synchronization batch of at most 256
 * records. When more upstream work remains, the port schedules another task at
 * the back of the Data Lane so registrations and other node work get a turn in
 * between.</p>
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
        port.handleNextOnDataLane();
    }
}
