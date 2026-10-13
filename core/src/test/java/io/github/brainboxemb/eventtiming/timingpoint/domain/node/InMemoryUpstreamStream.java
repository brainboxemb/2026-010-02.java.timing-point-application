package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Test mailbox for the node-scoped UpstreamStream contract.
 */
final class InMemoryUpstreamStream implements UpstreamStream {
    private final Queue<UpstreamData> inbound =
            new ArrayDeque<UpstreamData>();
    private final Queue<UpstreamData> outbound =
            new ArrayDeque<UpstreamData>();
    private boolean available = true;
    private Runnable onNextSend;

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public boolean hasMessage() {
        return !inbound.isEmpty();
    }

    @Override
    public UpstreamData read() {
        UpstreamData data = inbound.poll();
        if (data == null) {
            throw new IllegalStateException(
                    "no inbound UpstreamData available");
        }
        return data;
    }

    @Override
    public void send(
            UpstreamData message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null");
        }
        outbound.add(message);

        Runnable action = onNextSend;
        onNextSend = null;
        if (action != null) {
            action.run();
        }
    }

    void receive(
            UpstreamData message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null");
        }
        inbound.add(message);
    }

    UpstreamData takeSent() {
        UpstreamData data = outbound.poll();
        if (data == null) {
            throw new IllegalStateException(
                    "no outbound UpstreamData available");
        }
        return data;
    }

    void onNextSend(
            Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException(
                    "action must not be null");
        }
        onNextSend = action;
    }

    int sentCount() {
        return outbound.size();
    }

    void setAvailable(
            boolean available) {
        this.available = available;
    }
}
