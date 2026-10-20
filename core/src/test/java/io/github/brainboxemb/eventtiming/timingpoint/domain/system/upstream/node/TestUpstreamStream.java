package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.ArrayDeque;
import java.util.Queue;

/** Small non-blocking stream used by upstream helper unit tests. */
final class TestUpstreamStream implements UpstreamStream {
    private final Queue<UpstreamData> inbound = new ArrayDeque<UpstreamData>();
    private final Queue<UpstreamData> outbound = new ArrayDeque<UpstreamData>();

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean hasMessage() {
        return !inbound.isEmpty();
    }

    @Override
    public UpstreamData read() {
        UpstreamData data = inbound.poll();
        if (data == null) {
            throw new IllegalStateException("no inbound UpstreamData available");
        }
        return data;
    }

    @Override
    public void send(UpstreamData message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        outbound.add(message);
    }

    UpstreamData takeSent() {
        UpstreamData data = outbound.poll();
        if (data == null) {
            throw new IllegalStateException("no outbound UpstreamData available");
        }
        return data;
    }

    int sentCount() {
        return outbound.size();
    }
}

