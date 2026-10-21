package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.ArrayDeque;
import java.util.Queue;

/** Small semantic send seam used by upstream protocol-helper unit tests. */
final class TestUpstreamStream {
    private final Queue<UpstreamData> outbound =
            new ArrayDeque<UpstreamData>();
    private boolean available = true;

    boolean isAvailable() {
        return available;
    }

    void setAvailable(
            boolean available) {
        this.available = available;
    }

    void send(
            UpstreamData message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null");
        }
        outbound.add(message);
    }

    UpstreamData takeSent() {
        UpstreamData data =
                outbound.poll();
        if (data == null) {
            throw new IllegalStateException(
                    "no outbound UpstreamData available");
        }
        return data;
    }

    int sentCount() {
        return outbound.size();
    }
}
