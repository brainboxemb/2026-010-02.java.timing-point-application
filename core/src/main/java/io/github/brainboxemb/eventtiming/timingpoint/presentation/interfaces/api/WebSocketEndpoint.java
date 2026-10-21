package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatus;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.EngineerApiConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * IF-03 WebSocket transport.
 *
 * <p>Normal event clients are server-to-client only. The dedicated
 * {@code role=upstream} connection is full-duplex: it receives semantic
 * UpstreamData from SI-02 and carries outbound UpstreamData events on the same
 * WebSocket. Historical TimingData recovery remains an upstream protocol
 * responsibility rather than an IF-03 event replay concern.</p>
 */
public final class WebSocketEndpoint implements AutoCloseable {
    public static final String EVENTS_PATH = "/api/v1/events";
    public static final String UPSTREAM_EVENTS_PATH =
            EVENTS_PATH + "?role=upstream";

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketEndpoint.class);
    private static final int MAX_INBOUND_FRAME_BYTES = 64 * 1024;
    private static final long START_TIMEOUT_MILLIS = 3000L;

    private final String bindAddress;
    private final int port;
    private final PresentationGateway presentationGateway;
    private final Clock clock;
    private final TimingDataCodec timingDataCodec;
    private final UpstreamDataCodec upstreamDataCodec;
    private final List<EngineerApiConnector> upstreamConnectors;
    private final WebSocketOutboundDelivery outboundDelivery;
    private final AtomicReference<WebSocket> upstreamConnection =
            new AtomicReference<WebSocket>();
    private final AtomicReference<EngineerApiConnector.Output> upstreamOutput =
            new AtomicReference<EngineerApiConnector.Output>();

    private Server server;

    public WebSocketEndpoint(
            String bindAddress,
            int port,
            PresentationGateway presentationGateway) {
        this(
                bindAddress,
                port,
                presentationGateway,
                Collections.<EngineerApiConnector>emptyList(),
                Clock.systemUTC());
    }

    public WebSocketEndpoint(
            String bindAddress,
            int port,
            PresentationGateway presentationGateway,
            List<EngineerApiConnector> upstreamConnectors) {
        this(
                bindAddress,
                port,
                presentationGateway,
                upstreamConnectors,
                Clock.systemUTC());
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
        this(
                bindAddress,
                port,
                presentationGateway,
                Collections.<EngineerApiConnector>emptyList(),
                clock);
    }

    WebSocketEndpoint(
            String bindAddress,
            int port,
            PresentationGateway presentationGateway,
            List<EngineerApiConnector> upstreamConnectors,
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
        if (upstreamConnectors == null) {
            throw new IllegalArgumentException("upstreamConnectors must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }

        this.bindAddress = bindAddress.trim();
        this.port = port;
        this.presentationGateway = presentationGateway;
        this.upstreamConnectors =
                Collections.unmodifiableList(
                        new ArrayList<EngineerApiConnector>(
                                upstreamConnectors));
        this.clock = clock;
        this.timingDataCodec = new DefaultTimingDataCodec();
        this.upstreamDataCodec =
                new DefaultUpstreamDataCodec(
                        timingDataCodec);
        this.outboundDelivery =
                new WebSocketOutboundDelivery();

        bindApplicationEvents();
    }

    /** Starts the WebSocket listener after constructor-time application binding. */
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
        broadcastStatus("STATUS_CHANGED");
    }

    /**
     * Composition-wired application event callback.
     */
    private void onTimingNodeStatusChanged(
            TimingNodeStatus status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "status must not be null");
        }
        broadcastStatus("STATUS_CHANGED");
    }

    /**
     * Composition-wired application event callback.
     */
    private void onTimingDataCommitted(
            TimingRecord record) {
        Server current = currentServer();
        if (current == null) {
            return;
        }
        try {
            current.broadcastEvent(
                    MessageWriter.timingDataEvent(
                            clock.instant(),
                            record,
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
    private void onConfigurationChanged(
            ConfigurationControl.Change change) {
        Server current = currentServer();
        if (current != null) {
            current.broadcastEvent(
                    MessageWriter.configurationChangedEvent(
                            clock.instant(),
                            change));
        }
    }

    /**
     * Binds the endpoint to the transport-independent application event sources.
     *
     * <p>The endpoint owns these subscriptions because consuming gateway events is
     * intrinsic adapter behaviour; Runtime only constructs and starts the adapter.</p>
     */
    private void bindApplicationEvents() {
        for (TimingNodeProxy timingNode : presentationGateway.timingNodes()) {
            timingNode.STATUS_CHANGED.subscribe(this::onTimingNodeStatusChanged);
            timingNode.NEW_DATA_ENTRY.subscribe(this::onTimingDataCommitted);
        }
        presentationGateway.configuration()
                .changes()
                .subscribe(this::onConfigurationChanged);
    }

    private synchronized Server currentServer() {
        return server;
    }

    private void broadcastStatus(
            String eventType) {
        Server current = currentServer();
        if (current != null) {
            current.broadcastEvent(
                    MessageWriter.statusEvent(
                            eventType,
                            clock.instant(),
                            presentationGateway.timingNodes()));
        }
    }

    private String snapshotJson() {
        return MessageWriter.statusEvent(
                "STATUS_SNAPSHOT",
                clock.instant(),
                presentationGateway.timingNodes());
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
        } finally {
            disconnectUpstreamPeer(
                    upstreamConnection.get());
        }
    }

    private void disconnectUpstreamPeer(
            WebSocket connection) {
        if (connection == null
                || !upstreamConnection.compareAndSet(
                        connection,
                        null)) {
            return;
        }

        EngineerApiConnector.Output peer =
                upstreamOutput.getAndSet(null);
        if (peer != null) {
            for (EngineerApiConnector connector
                    : upstreamConnectors) {
                connector.disconnect(peer);
            }
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
            String resource =
                    handshake.getResourceDescriptor();
            boolean upstreamRole =
                    UPSTREAM_EVENTS_PATH.equals(resource);
            if (!upstreamRole
                    && !EVENTS_PATH.equals(resource)) {
                connection.close(
                        CloseFrame.POLICY_VALIDATION,
                        "Unknown IF-03 WebSocket resource");
                return;
            }
            if (upstreamRole
                    && !upstreamConnection.compareAndSet(
                            null,
                            connection)) {
                connection.close(
                        CloseFrame.POLICY_VALIDATION,
                        "Engineering upstream peer is already connected");
                return;
            }

            outboundDelivery.connected(
                    connection);
            try {
                /*
                 * The status snapshot is always the first event. Only after that
                 * baseline has been accepted does role=upstream make the
                 * Engineering connector operational.
                 */
                sendEvent(
                        connection,
                        snapshotJson());

                if (upstreamRole) {
                    EngineerApiConnector.Output peer =
                            message ->
                                    sendUpstreamData(
                                            connection,
                                            message);
                    upstreamOutput.set(peer);

                    for (EngineerApiConnector connector
                            : upstreamConnectors) {
                        if (!connector.connect(peer)) {
                            throw new IllegalStateException(
                                    "Engineering upstream connector already has a peer");
                        }
                    }
                }
            } catch (RuntimeException ex) {
                disconnectUpstreamPeer(connection);
                LOG.warn(
                        "Unable to initialize IF-03 WebSocket client",
                        ex);
                connection.close(
                        CloseFrame.UNEXPECTED_CONDITION,
                        "Unable to initialize IF-03 WebSocket client");
            }
        }

        @Override
        public void onClose(WebSocket connection, int code, String reason, boolean remote) {
            disconnectUpstreamPeer(connection);
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
            if (connection == upstreamConnection.get()) {
                receiveUpstreamData(
                        connection,
                        message);
                return;
            }
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
            deliverEvent(
                    connection,
                    message);
        }

        /**
         * Decodes one semantic UpstreamData object received on the upstream-role
         * WebSocket and hands it to the connector/gateway routing chain.
         */
        private void receiveUpstreamData(
                WebSocket connection,
                String message) {
            final UpstreamData upstreamData;
            try {
                upstreamData =
                        upstreamDataCodec.decode(
                                message.getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8));
            } catch (UpstreamDataCodec.CodecException ex) {
                connection.close(
                        CloseFrame.POLICY_VALIDATION,
                        "Invalid UpstreamData");
                return;
            }

            UpstreamGateway.ReceiveResult result =
                    routeUpstreamData(
                            upstreamData);
            switch (result) {
                case ACCEPTED:
                    return;
                case UNKNOWN_NODE:
                    connection.close(
                            CloseFrame.POLICY_VALIDATION,
                            "Unknown upstream NodeId");
                    return;
                case INPUT_FULL:
                case INPUT_REJECTED:
                    connection.close(
                            WebSocketOutboundDelivery.TRY_AGAIN_LATER_CLOSE_CODE,
                            "Upstream input is busy");
                    return;
                default:
                    throw new IllegalStateException(
                            "Unsupported upstream receive result "
                                    + result);
            }
        }

        /**
         * Routes one inbound message to the connector whose gateway owns its NodeId.
         *
         * <p>The current runtime binds each NodeUpstream to one connector. Multiple
         * connectors can coexist for different nodes/systems without exposing that
         * selection to the WebSocket peer.</p>
         */
        private UpstreamGateway.ReceiveResult routeUpstreamData(
                UpstreamData message) {
            UpstreamGateway.ReceiveResult result =
                    UpstreamGateway.ReceiveResult.UNKNOWN_NODE;
            for (EngineerApiConnector connector
                    : upstreamConnectors) {
                UpstreamGateway.ReceiveResult candidate =
                        connector.receive(message);
                if (candidate
                        != UpstreamGateway.ReceiveResult.UNKNOWN_NODE) {
                    return candidate;
                }
                result = candidate;
            }
            return result;
        }

        private boolean sendUpstreamData(
                WebSocket connection,
                UpstreamData message) {
            final String event;
            try {
                event =
                        MessageWriter.upstreamDataEvent(
                                clock.instant(),
                                message,
                                upstreamDataCodec);
            } catch (UpstreamDataCodec.CodecException ex) {
                LOG.warn(
                        "Could not encode UpstreamData for IF-03",
                        ex);
                closeAfterDeliveryFailure(connection);
                return false;
            }

            return deliverEvent(
                    connection,
                    event)
                    == WebSocketOutboundDelivery.SendResult.SENT;
        }

        private WebSocketOutboundDelivery.SendResult deliverEvent(
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
                return result;
            } catch (RuntimeException ex) {
                LOG.debug(
                        "IF-03 WebSocket event send failed for {}",
                        connection.getRemoteSocketAddress(),
                        ex);
                closeAfterDeliveryFailure(
                        connection);
                return WebSocketOutboundDelivery.SendResult.NOT_CONNECTED;
            }
        }

        /**
         * Best-effort cleanup after one client send failure.
         *
         * <p>A close failure is diagnostic only; it must not escape the
         * application event callback and prevent delivery to sibling clients.</p>
         */
        private void closeAfterDeliveryFailure(
                WebSocket connection) {
            try {
                connection.close(
                        CloseFrame.UNEXPECTED_CONDITION,
                        "Unable to deliver IF-03 event");
            } catch (RuntimeException closeFailure) {
                LOG.debug(
                        "Unable to close failed IF-03 WebSocket client",
                        closeFailure);
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
