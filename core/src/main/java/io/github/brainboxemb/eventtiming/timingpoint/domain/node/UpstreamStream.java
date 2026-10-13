package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * Non-blocking semantic mailbox between one TimingNode and its upstream
 * transport adapter.
 *
 * <p>The stream is already scoped to one TimingNode. Real transport I/O stays
 * outside the TimingNode serial lane: read and send operate only on local
 * inbound/outbound queues owned by the adapter.</p>
 */
public interface UpstreamStream {

    /** Returns whether the upstream communication/session is currently usable. */
    boolean isAvailable();

    /** Returns whether one inbound semantic message can be read without blocking. */
    boolean hasMessage();

    /**
     * Consumes one inbound semantic message.
     *
     * @throws IllegalStateException when no message is available.
     */
    UpstreamData read();

    /**
     * Enqueues one outbound semantic message without performing blocking
     * transport I/O.
     */
    void send(UpstreamData message);
}
