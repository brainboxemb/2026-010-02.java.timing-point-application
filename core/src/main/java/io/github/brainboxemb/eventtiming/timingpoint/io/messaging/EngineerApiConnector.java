package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Engineering-API transport/session implementation of UpstreamConnector.
 *
 * <p>The connector owns the active engineering WebSocket session, bounded
 * outbound delivery queue and heartbeat cadence. It transports UpstreamMessage
 * envelopes and never inspects the semantic payload. Node queues and NodeId routing
 * belong to UpstreamGateway.</p>
 */
public final class EngineerApiConnector implements UpstreamConnector {
    private static final Logger LOG =
            LoggerFactory.getLogger(
                    EngineerApiConnector.class);
    private static final Duration HEARTBEAT_PERIOD =
            Duration.ofSeconds(30L);
    static final int DEFAULT_OUTPUT_CAPACITY = 64;

    /** Non-blocking delivery boundary implemented by the IF-03 WebSocket adapter. */
    public interface Output {
        boolean send(UpstreamMessage message);
    }

    private final ScheduledExecutorService scheduler;
    private final long heartbeatPeriodNanos;
    private final int outputCapacity;
    private final Queue<UpstreamMessage> outputQueue =
            new ArrayDeque<UpstreamMessage>();

    private UpstreamConnector.Listener listener;
    private Output output;
    private ScheduledFuture<?> heartbeatTask;

    public EngineerApiConnector(
            ScheduledExecutorService scheduler) {
        this(
                scheduler,
                DEFAULT_OUTPUT_CAPACITY,
                HEARTBEAT_PERIOD);
    }

    EngineerApiConnector(
            ScheduledExecutorService scheduler,
            int outputCapacity,
            Duration heartbeatPeriod) {
        failIfInvalidArgument(scheduler != null, "scheduler must not be null");
        failIfInvalidArgument(outputCapacity > 0, "outputCapacity must be positive");
        failIfInvalidArgument(heartbeatPeriod != null
                && !heartbeatPeriod.isZero()
                && !heartbeatPeriod.isNegative(),
                "heartbeatPeriod must be positive");
        this.scheduler = scheduler;
        this.outputCapacity = outputCapacity;
        this.heartbeatPeriodNanos = heartbeatPeriod.toNanos();
    }

    @Override
    public synchronized void bind(
            UpstreamConnector.Listener newListener) {
        failIfInvalidArgument(newListener != null, "listener must not be null");
        failIfIllegalState(listener == null, "Engineering connector is already bound");
        failIfIllegalState(output == null, "Cannot bind while connector is connected");
        listener = newListener;
    }

    /**
     * Activates one engineering WebSocket peer after its status snapshot.
     *
     * <p>Connection lifecycle is reported to UpstreamGateway before heartbeat
     * cadence starts, so every bound NodeUpstream can first queue source-head
     * reconciliation for the new session.</p>
     */
    public boolean connect(
            Output newOutput) {
        failIfInvalidArgument(newOutput != null, "output must not be null");

        UpstreamConnector.Listener currentListener;
        synchronized (this) {
            failIfIllegalState(listener != null,
                    "Engineering connector is not bound to an UpstreamGateway");
            if (output != null) {
                return false;
            }
            outputQueue.clear();
            output = newOutput;
            currentListener = listener;
        }

        try {
            currentListener.connected();
            startHeartbeat();
            return true;
        } catch (RuntimeException | Error failure) {
            disconnect(newOutput);
            throw failure;
        }
    }

    /** Ends the active engineering session and reports the session boundary to the gateway. */
    public void disconnect(
            Output currentOutput) {
        UpstreamConnector.Listener currentListener = null;
        synchronized (this) {
            if (currentOutput != null
                    && output == currentOutput) {
                clearSession();
                currentListener = listener;
            }
        }

        if (currentListener != null) {
            currentListener.disconnected();
        }
    }

    /**
     * Hands one decoded routing envelope to UpstreamGateway.
     *
     * <p>The connector neither routes by NodeId nor inspects the semantic payload.</p>
     */
    public UpstreamGateway.ReceiveResult receive(
            UpstreamMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");

        UpstreamConnector.Listener currentListener;
        synchronized (this) {
            failIfIllegalState(output != null,
                    "Engineering upstream peer is not connected");
            currentListener = listener;
        }
        return currentListener.receive(message);
    }

    @Override
    public synchronized boolean isAvailable() {
        return output != null;
    }

    /**
     * Queues one outbound routing envelope for the active WebSocket peer.
     *
     * <p>WebSocket socket-drain behavior remains below Output; this queue only
     * bounds connector-side delivery.</p>
     */
    @Override
    public synchronized void send(
            UpstreamMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");
        failIfIllegalState(output != null, "Engineering upstream peer is not connected");

        if (outputQueue.size() >= outputCapacity) {
            throw new RejectedExecutionException(
                    "Engineering upstream output queue is full");
        }

        outputQueue.add(message);
        drainOutput();
    }

    private void drainOutput() {
        while (!outputQueue.isEmpty()) {
            UpstreamMessage message =
                    outputQueue.peek();
            final boolean accepted;
            try {
                accepted =
                        output.send(message);
            } catch (RuntimeException | Error failure) {
                clearSession();
                listener.disconnected();
                throw failure;
            }

            if (!accepted) {
                clearSession();
                listener.disconnected();
                throw new IllegalStateException(
                        "Engineering upstream peer rejected output");
            }
            outputQueue.remove();
        }
    }

    private synchronized void startHeartbeat() {
        failIfIllegalState(output != null,
                "Cannot start heartbeat without an Engineering upstream peer");
        failIfIllegalState(heartbeatTask == null,
                "Engineering upstream heartbeat is already scheduled");

        heartbeatTask =
                scheduler.scheduleAtFixedRate(
                        this::heartbeatTick,
                        0L,
                        heartbeatPeriodNanos,
                        TimeUnit.NANOSECONDS);
    }

    /**
     * Emits one connector/session cadence signal.
     *
     * <p>NodeUpstream owns PING protocol state. This callback merely tells the
     * gateway that the active connector cycle reached its next heartbeat tick.</p>
     */
    private synchronized void heartbeatTick() {
        if (output == null) {
            return;
        }

        try {
            listener.heartbeatTick();
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Engineering upstream heartbeat callback failed",
                    failure);
        }
    }

    /** Clears connector-owned state for the current transport session. */
    private void clearSession() {
        ScheduledFuture<?> currentHeartbeat =
                heartbeatTask;
        heartbeatTask = null;
        if (currentHeartbeat != null) {
            currentHeartbeat.cancel(false);
        }

        output = null;
        outputQueue.clear();
    }
}
