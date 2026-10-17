package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe test mailbox for the node-scoped UpstreamStream contract.
 *
 * <p>Production upstream work is asynchronous. Tests therefore wait briefly for
 * outbound data instead of assuming that input handling completes inline on the
 * caller thread.</p>
 */
final class InMemoryUpstreamStream implements UpstreamStream {
    private static final long SEND_WAIT_MILLIS = 1000L;

    private final Queue<UpstreamData> inbound =
            new ArrayDeque<UpstreamData>();
    private final Queue<UpstreamData> outbound =
            new ArrayDeque<UpstreamData>();
    private boolean available = true;
    private Runnable onNextSend;

    @Override
    public synchronized boolean isAvailable() {
        return available;
    }

    @Override
    public synchronized boolean hasMessage() {
        return !inbound.isEmpty();
    }

    @Override
    public synchronized UpstreamData read() {
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

        Runnable action;
        synchronized (this) {
            outbound.add(message);
            action = onNextSend;
            onNextSend = null;
            notifyAll();
        }

        if (action != null) {
            action.run();
        }
    }

    synchronized void receive(
            UpstreamData message) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null");
        }
        inbound.add(message);
    }

    UpstreamData takeSent() {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                SEND_WAIT_MILLIS);

        synchronized (this) {
            while (outbound.isEmpty()) {
                long remainingNanos =
                        deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new IllegalStateException(
                            "no outbound UpstreamData available");
                }

                try {
                    TimeUnit.NANOSECONDS.timedWait(
                            this,
                            remainingNanos);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while waiting for outbound UpstreamData",
                            ex);
                }
            }
            return outbound.remove();
        }
    }

    synchronized void onNextSend(
            Runnable action) {
        if (action == null) {
            throw new IllegalArgumentException(
                    "action must not be null");
        }
        onNextSend = action;
    }

    synchronized int sentCount() {
        return outbound.size();
    }

    synchronized void setAvailable(
            boolean available) {
        this.available = available;
    }
}
