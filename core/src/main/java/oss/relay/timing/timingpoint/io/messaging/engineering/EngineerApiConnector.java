package oss.relay.timing.timingpoint.io.messaging.engineering;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingpoint.io.messaging.UpstreamConnector;
import oss.relay.timing.timingpoint.io.messaging.UpstreamGateway;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Engineering IF-03 transport/session implementation of UpstreamConnector.
 *
 * <p>This connector owns the active WebSocket peer and bounded outbound delivery. NodeId
 * routing stays in UpstreamGateway; IF-06 semantics and heartbeat state stay in NodeUpstream.</p>
 */
public final class EngineerApiConnector extends UpstreamConnector {
    private static final Logger LOG = LoggerFactory.getLogger(EngineerApiConnector.class);
    static final int DEFAULT_OUTPUT_CAPACITY = 64;

    /** The WebSocket adapter may reject a write without blocking the connector. */
    public interface Output {
        boolean send(UpstreamApiMessage message);
    }

    private final int outputCapacity;
    private final Queue<UpstreamApiMessage> outputQueue = new ArrayDeque<UpstreamApiMessage>();

    private Output output;

    /** The default bound protects the connector from unbounded retained outbound traffic. */
    public EngineerApiConnector() {
        this(DEFAULT_OUTPUT_CAPACITY);
    }

    /** Package-local capacity selection keeps queue-bound verification deterministic. */
    EngineerApiConnector(int outputCapacity) {

        failIfInvalidArgument(outputCapacity > 0, "outputCapacity must be positive");
        this.outputCapacity = outputCapacity;
    }

    /**
     * The peer becomes authoritative before CONNECTED is published, so subscribers may send
     * reconciliation traffic immediately from the connection event.
     */
    public boolean connect(Output newOutput) {

        failIfInvalidArgument(newOutput != null, "output must not be null");

        requireBound();
        synchronized (this) {
            if (output != null) {
                return false;
            }
            outputQueue.clear();
            output = newOutput;
        }

        LOG.info("Upstream connected: transport=engineering-api");
        publishConnected();
        return true;
    }

    /** A stale peer callback cannot disconnect a newer active Engineering session. */
    public void disconnect(Output currentOutput) {

        boolean disconnected;
        synchronized (this) {
            disconnected = currentOutput != null && output == currentOutput;
            if (disconnected) {
                clearSession();
            }
        }

        if (disconnected) {
            LOG.info(
                    "Upstream disconnected: transport=engineering-api, reason=peer-closed");
            publishDisconnected();
        }
    }

    /** Inbound payload remains encoded; only the already extracted NodeId is used for routing. */
    public UpstreamGateway.ReceiveResult receive(UpstreamApiMessage message) {

        failIfInvalidArgument(message != null, "message must not be null");

        synchronized (this) {
            failIfIllegalState(output != null, "Engineering upstream peer is not connected");
        }
        return receiveInput(message.nodeId(), message.payload());
    }

    @Override
    public synchronized boolean isAvailable() {
        return output != null;
    }

    /** The bounded queue preserves connector order and rejects overload instead of growing indefinitely. */
    @Override
    public synchronized void send(NodeId nodeId, String encodedMessage) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(encodedMessage != null, "encodedMessage must not be null");
        failIfIllegalState(output != null, "Engineering upstream peer is not connected");

        if (outputQueue.size() >= outputCapacity) {
            throw new RejectedExecutionException("Engineering upstream output queue is full");
        }

        outputQueue.add(UpstreamApiMessage.createOutboundMessage(nodeId, encodedMessage));
        drainOutput();
    }

    /** Delivery failure invalidates the current session before the failure escapes to its caller. */
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

    /** Disconnect observers see the failed session only after connector-owned state is cleared. */
    private void disconnectAfterDeliveryFailure() {
        clearSession();
        LOG.info(
                "Upstream disconnected: transport=engineering-api, reason=delivery-failure");
        publishDisconnected();
    }

    /** Session state is cleared before any DISCONNECTED event is published. */
    private void clearSession() {
        output = null;
        outputQueue.clear();
    }
}
