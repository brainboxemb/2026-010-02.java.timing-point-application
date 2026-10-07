package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatus;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.IExtension;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-to-client IF-03 event stream.
 *
 * <p>The endpoint subscribes only to the transport-independent
 * {@link PresentationGateway}. It sends one complete STATUS_SNAPSHOT when a client
 * connects, then broadcasts authoritative STATUS_CHANGED and
 * TIMING_DATA_COMMITTED notifications. Slow clients are disconnected before
 * their transport backlog can grow without bound. Historical TimingData is
 * deliberately not replayed here; reconnect recovery uses the HTTP history
 * resource.</p>
 */
public final class WebSocketEndpoint implements AutoCloseable {
    public static final String EVENTS_PATH = "/api/v1/events";

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketEndpoint.class);
    private static final int MAX_INBOUND_FRAME_BYTES = 64 * 1024;
    private static final long START_TIMEOUT_MILLIS = 3000L;

    private final String bindAddress;
    private final int port;
    private final PresentationGateway presentationGateway;
    private final TimingNodeProxy timingNode;
    private final Clock clock;
    private final TimingDataCodec timingDataCodec;
    private final WebSocketOutboundDelivery outboundDelivery;

    private Server server;

    public WebSocketEndpoint(
            String bindAddress,
            int port,
            PresentationGateway presentationGateway) {
        this(bindAddress, port, presentationGateway, Clock.systemUTC());
    }

    /**
     * Package-private deterministic-clock seam for event timestamp tests.
     *
     * <p>Production composition always uses the public constructor.</p>
     */
    WebSocketEndpoint(
            String bindAddress,
            int port,
            PresentationGateway presentationGateway,
            Clock clock) {
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("bindAddress must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (presentationGateway == null) {
            throw new IllegalArgumentException("presentationGateway must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.bindAddress = bindAddress.trim();
        this.port = port;
        this.presentationGateway = presentationGateway;
        this.timingNode = presentationGateway.timingNode();
        this.clock = clock;
        this.timingDataCodec = new DefaultTimingDataCodec();
        this.outboundDelivery =
                new WebSocketOutboundDelivery();
    }

    /**
     * Starts the WebSocket listener.
     *
     * <p>Application-event wiring is completed by PresentationRuntime during
     * composition, before this endpoint is activated.</p>
     */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("WebSocket IF-03 server is already started");
        }

        InetSocketAddress address =
                new InetSocketAddress(InetAddress.getByName(bindAddress), port);
        Server candidate = new Server(address);
        candidate.start();

        try {
            if (!candidate.awaitStarted(START_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                stopCandidate(candidate);
                throw new IOException("Timed out starting WebSocket IF-03 server");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            stopCandidate(candidate);
            throw new IOException("Interrupted while starting WebSocket IF-03 server", ex);
        }

        Exception failure = candidate.startFailure();
        if (failure != null) {
            stopCandidate(candidate);
            throw new IOException("Unable to start WebSocket IF-03 server", failure);
        }

        server = candidate;

        LOG.info(
                "WebSocket IF-03 listening on {}:{}{}",
                bindAddress,
                candidate.getPort(),
                EVENTS_PATH);
    }

    public synchronized int boundPort() {
        if (server == null) {
            throw new IllegalStateException("WebSocket IF-03 server is not started");
        }
        return server.getPort();
    }

    /**
     * Broadcasts the current complete status.
     *
     * <p>Kept as a small diagnostic/test hook. Normal Step-4 status changes are
     * published automatically from the PresentationGateway subscription.</p>
     */
    public void publishStatusChanged() {
        onTimingNodeStatusChanged(timingNode.status());
    }

    /**
     * Composition-wired application event callback.
     */
    public void onTimingNodeStatusChanged(
            TimingNodeStatus status) {
        Server current = currentServer();
        if (current != null) {
            current.broadcastEvent(
                    MessageWriter.statusEvent(
                            "STATUS_CHANGED",
                            clock.instant(),
                            status));
        }
    }

    /**
     * Composition-wired application event callback.
     */
    public void onTimingDataCommitted(
            TimingData data) {
        Server current = currentServer();
        if (current == null) {
            return;
        }
        try {
            current.broadcastEvent(
                    MessageWriter.timingDataEvent(
                            clock.instant(),
                            data,
                            timingDataCodec));
        } catch (TimingDataCodec.CodecException ex) {
            // Turn codec failure into an ordinary listener RuntimeException.
            // TimingNode's post-commit Event isolates/reports this without
            // rolling back the already durable TimingData commit.
            throw new IllegalStateException(
                    "Could not encode committed TimingData for IF-03 event",
                    ex);
        }
    }

    /**
     * Composition-wired application event callback.
     */
    public void onConfigurationChanged(
            ConfigurationControl.Change change) {
        Server current = currentServer();
        if (current != null) {
            current.broadcastEvent(
                    MessageWriter.configurationChangedEvent(
                            clock.instant(),
                            change));
        }
    }

    private synchronized Server currentServer() {
        return server;
    }

    private String snapshotJson() {
        return MessageWriter.statusEvent(
                "STATUS_SNAPSHOT",
                clock.instant(),
                timingNode.status());
    }

    @Override
    public synchronized void close() {
        Server current = server;
        server = null;
        if (current == null) {
            return;
        }
        try {
            current.stop(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stopCandidate(Server candidate) {
        try {
            candidate.stop(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private final class Server extends WebSocketServer {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<Exception> startFailure = new AtomicReference<>();

        private Server(InetSocketAddress address) {
            super(
                    address,
                    1,
                    Collections.<Draft>singletonList(
                            new Draft_6455(
                                    Collections.<IExtension>emptyList(),
                                    MAX_INBOUND_FRAME_BYTES)));
        }

        private boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
            return started.await(timeout, unit);
        }

        private Exception startFailure() {
            return startFailure.get();
        }

        @Override
        public void onOpen(WebSocket connection, ClientHandshake handshake) {
            if (!EVENTS_PATH.equals(handshake.getResourceDescriptor())) {
                connection.close(
                        CloseFrame.POLICY_VALIDATION,
                        "Unknown IF-03 WebSocket resource");
                return;
            }
            outboundDelivery.connected(
                    connection);
            try {
                sendEvent(
                        connection,
                        snapshotJson());
            } catch (RuntimeException ex) {
                LOG.warn(
                        "Unable to create initial IF-03 status snapshot",
                        ex);
                connection.close(
                        CloseFrame.UNEXPECTED_CONDITION,
                        "Unable to create status snapshot");
            }
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            outboundDelivery.disconnected(
                    connection);
            LOG.debug(
                    "IF-03 WebSocket client closed code={} remote={} reason={}",
                    code,
                    remote,
                    reason);
        }

        @Override
        public void onMessage(WebSocket connection, String message) {
            rejectClientMessage(connection);
        }

        @Override
        public void onMessage(WebSocket connection, ByteBuffer message) {
            rejectClientMessage(connection);
        }

        /**
         * Broadcasts one already encoded event without blocking for socket drain.
         *
         * <p>One slow/broken client is handled independently so it cannot stop
         * delivery to the remaining connected clients.</p>
         */
        private void broadcastEvent(
                String message) {
            for (WebSocket connection : getConnections()) {
                sendEvent(
                        connection,
                        message);
            }
        }

        private void sendEvent(
                WebSocket connection,
                String message) {
            try {
                WebSocketOutboundDelivery.SendResult result =
                        outboundDelivery.send(
                                connection,
                                message);
                if (result
                        == WebSocketOutboundDelivery.SendResult.OVERLOADED) {
                    LOG.warn(
                            "Disconnecting slow IF-03 WebSocket client {} after outbound backlog limit",
                            connection.getRemoteSocketAddress());
                }
            } catch (RuntimeException ex) {
                LOG.debug(
                        "IF-03 WebSocket event send failed for {}",
                        connection.getRemoteSocketAddress(),
                        ex);
                connection.close(
                        CloseFrame.UNEXPECTED_CONDITION,
                        "Unable to deliver IF-03 event");
            }
        }

        private void rejectClientMessage(WebSocket connection) {
            connection.close(
                    CloseFrame.POLICY_VALIDATION,
                    "IF-03 event stream is server-to-client only");
        }

        @Override
        public void onError(WebSocket connection, Exception ex) {
            if (started.getCount() > 0) {
                startFailure.compareAndSet(null, ex);
                started.countDown();
            }
            if (connection == null) {
                LOG.warn("IF-03 WebSocket server failed", ex);
            } else {
                LOG.debug("IF-03 WebSocket connection failed", ex);
            }
        }

        @Override
        public void onStart() {
            started.countDown();
        }
    }
}
