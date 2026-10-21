package io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.Event;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Engineering-API transport/session implementation of {@link UpstreamConnector}.
 *
 * <p>This connector owns only the active IF-03 WebSocket session and its bounded outbound delivery queue.
 * UpstreamGateway owns NodeId routing; NodeUpstream owns IF-06 semantics and heartbeat protocol state.</p>
 *
 * <p>Session lifecycle is exposed as typed post-fact events. Inbound API data uses the gateway's explicit
 * {@link UpstreamGateway.ConnectorPort} because queue admission has a meaningful return value.</p>
 */
public final class EngineerApiConnector implements UpstreamConnector {
    static final int DEFAULT_OUTPUT_CAPACITY = 64;

    /** Non-blocking delivery boundary implemented by the IF-03 WebSocket adapter. */
    public interface Output {
        boolean send(UpstreamApiMessage message);
    }

    private final int outputCapacity;
    private final Queue<UpstreamApiMessage> outputQueue = new ArrayDeque<UpstreamApiMessage>();
    private final Event<UpstreamConnector> connectedEvent = new Event<UpstreamConnector>();
    private final Event<UpstreamConnector> disconnectedEvent = new Event<UpstreamConnector>();

    private UpstreamGateway.ConnectorPort input;
    private Output output;

    public EngineerApiConnector() {
        this(DEFAULT_OUTPUT_CAPACITY);
    }

    EngineerApiConnector(int outputCapacity) {
        failIfInvalidArgument(outputCapacity > 0, "outputCapacity must be positive");
        this.outputCapacity = outputCapacity;
    }

    /**
     * Binds the command-style gateway input once during composition.
     *
     * <p>The connector cannot accept an upstream-role WebSocket before this binding exists because no application
     * routing target would be available.</p>
     */
    @Override
    public synchronized void bindInput(UpstreamGateway.ConnectorPort newInput) {
        failIfInvalidArgument(newInput != null, "input must not be null");
        failIfIllegalState(input == null, "Engineering connector input is already bound");
        failIfIllegalState(output == null, "Cannot bind input while connector is connected");
        input = newInput;
    }

    @Override
    public EventSource<UpstreamConnector> connectedEvent() {
        return connectedEvent;
    }

    @Override
    public EventSource<UpstreamConnector> disconnectedEvent() {
        return disconnectedEvent;
    }

    /**
     * Activates one engineering WebSocket peer after its status snapshot has been delivered.
     *
     * <p>The connection event is emitted only after the peer becomes the authoritative connector output, so
     * application-port observers can immediately send reconciliation traffic.</p>
     */
    public boolean connect(Output newOutput) {
        failIfInvalidArgument(newOutput != null, "output must not be null");

        synchronized (this) {
            failIfIllegalState(input != null, "Engineering connector is not bound to an UpstreamGateway");
            if (output != null) {
                return false;
            }
            outputQueue.clear();
            output = newOutput;
        }

        connectedEvent.emit(this);
        return true;
    }

    /** Ends the active engineering session and publishes one post-fact disconnect notification. */
    public void disconnect(Output currentOutput) {
        boolean disconnected;
        synchronized (this) {
            disconnected = currentOutput != null && output == currentOutput;
            if (disconnected) {
                clearSession();
            }
        }

        if (disconnected) {
            disconnectedEvent.emit(this);
        }
    }

    /**
     * Admits one inbound Engineering API message to the gateway.
     *
     * <p>Only NodeId extraction has happened at this point. The encoded IF-06 payload is still untouched.</p>
     */
    public UpstreamGateway.ReceiveResult receive(UpstreamApiMessage message) {
        failIfInvalidArgument(message != null, "message must not be null");

        UpstreamGateway.ConnectorPort currentInput;
        synchronized (this) {
            failIfIllegalState(output != null, "Engineering upstream peer is not connected");
            currentInput = input;
        }
        return currentInput.receive(message.nodeId(), message.payload());
    }

    @Override
    public synchronized boolean isAvailable() {
        return output != null;
    }

    /**
     * Queues one outbound message for the active WebSocket peer.
     *
     * <p>The routing NodeId is already resolved by the application port. The Engineering wrapper keeps that routing
     * context beside the encoded payload without introducing a generic messaging envelope.</p>
     */
    @Override
    public synchronized void send(NodeId nodeId, String encodedMessage) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");
        failIfIllegalState(output != null, "Engineering upstream peer is not connected");

        if (outputQueue.size() >= outputCapacity) {
            throw new RejectedExecutionException("Engineering upstream output queue is full");
        }

        outputQueue.add(UpstreamApiMessage.outbound(nodeId, encodedMessage));
        drainOutput();
    }

    /**
     * Delivers queued API values in connector order.
     *
     * <p>WebSocket socket-drain behavior remains below Output. A rejected or failing peer terminates this connector
     * session before the failure is propagated to the caller.</p>
     */
    private void drainOutput() {
        while (!outputQueue.isEmpty()) {
            UpstreamApiMessage message = outputQueue.peek();
            final boolean accepted;
            try {
                accepted = output.send(message);
            } catch (RuntimeException | Error failure) {
                disconnectAfterDeliveryFailure();
                throw failure;
            }

            if (!accepted) {
                disconnectAfterDeliveryFailure();
                throw new IllegalStateException("Engineering upstream peer rejected output");
            }
            outputQueue.remove();
        }
    }

    /** Clears the failed session and notifies gateway/application observers exactly once. */
    private void disconnectAfterDeliveryFailure() {
        clearSession();
        disconnectedEvent.emit(this);
    }

    /** Clears connector-owned state; callers publish the corresponding disconnect event after this mutation. */
    private void clearSession() {
        output = null;
        outputQueue.clear();
    }
}
