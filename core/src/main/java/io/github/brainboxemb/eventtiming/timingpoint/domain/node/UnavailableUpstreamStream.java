package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * Default stream used while no upstream transport has been composed.
 */
enum UnavailableUpstreamStream implements UpstreamStream {
    INSTANCE;

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean hasMessage() {
        return false;
    }

    @Override
    public UpstreamData read() {
        throw new IllegalStateException(
                "upstream communication is unavailable");
    }

    @Override
    public void send(UpstreamData message) {
        throw new IllegalStateException(
                "upstream communication is unavailable");
    }
}
