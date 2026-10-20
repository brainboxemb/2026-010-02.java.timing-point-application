package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/** Default gateway used while no production upstream transport is configured. */
enum UnavailableUpstreamGateway implements UpstreamGateway {
    INSTANCE;

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean hasMessage(NodeId nodeId) {
        return false;
    }

    @Override
    public UpstreamData read(NodeId nodeId) {
        throw new IllegalStateException(
                "upstream communication is unavailable");
    }

    @Override
    public void send(UpstreamData message) {
        throw new IllegalStateException(
                "upstream communication is unavailable");
    }
}
