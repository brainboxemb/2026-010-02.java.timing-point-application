package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy.AutomaticRegistrationAction;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RegistrationResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP/JSON transport for IF-03.
 *
 * <p>This adapter owns HTTP lifecycle, routing and response mapping. Inbound request
 * decoding is delegated to {@link HttpRequestReader}. All state-dependent decisions are
 * delegated to {@link PresentationGateway}; it never calls TimingNode directly.</p>
 */
public final class HttpEndpoint implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HttpEndpoint.class);
    private final String bindAddress;
    private final int port;
    private final PresentationGateway presentationGateway;
    private final TimingNodeProxy timingNode;
    private final TimingDataCodec timingDataCodec;
    private final HttpRequestReader requestReader = new HttpRequestReader();

    private HttpServer server;
    private ExecutorService executor;

    public HttpEndpoint(String bindAddress, int port, PresentationGateway presentationGateway) {
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("bindAddress must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (presentationGateway == null) {
            throw new IllegalArgumentException("presentationGateway must not be null");
        }
        this.bindAddress = bindAddress.trim();
        this.port = port;
        this.presentationGateway = presentationGateway;
        this.timingNode = presentationGateway.timingNode();
        this.timingDataCodec = new DefaultTimingDataCodec();
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("HTTP status server is already started");
        }

        HttpServer httpServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName(bindAddress), port), 0);
        ExecutorService httpExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tp-prl-api-http");
            thread.setDaemon(true);
            return thread;
        });
        httpServer.setExecutor(httpExecutor);
        httpServer.createContext("/", this::handle);
        httpServer.start();

        executor = httpExecutor;
        server = httpServer;
        LOG.info("HTTP IF-03 listening on {}:{}", bindAddress, httpServer.getAddress().getPort());
    }

    public synchronized int boundPort() {
        if (server == null) {
            throw new IllegalStateException("HTTP IF-03 server is not started");
        }
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            route(exchange);
        } catch (ResponseAlreadySent ignored) {
            // Method validation already wrote and closed the HTTP response.
        } catch (HttpRequestReader.RequestException ex) {
            sendJson(exchange, 400, MessageWriter.error(ex.code(), ex.getMessage()));
        } catch (OperationException ex) {
            sendOperationFailure(exchange, ex);
        } catch (RuntimeException ex) {
            LOG.warn("IF-03 request failed", ex);
            sendJson(
                    exchange,
                    500,
                    MessageWriter.error(
                            "INTERNAL_ERROR",
                            "Unexpected interface failure"));
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        if ("/api/v1/version".equals(path)) {
            requireMethod(exchange, "GET");
            sendJson(exchange, 200, MessageWriter.version(presentationGateway.version()));
            return;
        }
        if ("/api/v1/status".equals(path)) {
            requireMethod(exchange, "GET");
            sendJson(exchange, 200, MessageWriter.status(timingNode.status()));
            return;
        }
        if ("/api/v1/capabilities".equals(path)) {
            requireMethod(exchange, "GET");
            sendJson(
                    exchange,
                    200,
                    MessageWriter.capabilities(presentationGateway.capabilities()));
            return;
        }
        if (path.startsWith("/api/v1/node/")) {
            routeNode(exchange, path.substring("/api/v1/node/".length()));
            return;
        }
        if (path.startsWith("/api/v1/dev/node/")) {
            routeDevNode(exchange, path.substring("/api/v1/dev/node/".length()));
            return;
        }

        sendJson(
                exchange,
                404,
                MessageWriter.error("NOT_FOUND", "Unknown IF-03 resource"));
    }

    private void routeNode(HttpExchange exchange, String remainder) throws IOException {
        HttpRequestReader.NodeRoute route = requestReader.nodeRoute(remainder);
        if (route == null) {
            sendJson(
                    exchange,
                    404,
                    MessageWriter.error("NOT_FOUND", "Unknown TimingNode resource"));
            return;
        }
        requireCurrentNode(exchange, route.nodeId);

        if ("/open".equals(route.resource)) {
            requireMethod(exchange, "POST");
            handleOpen(exchange);
            return;
        }
        if ("/close".equals(route.resource)) {
            requireMethod(exchange, "POST");
            requestReader.requireEmptyBody(exchange);
            sendJson(
                    exchange,
                    200,
                    MessageWriter.result(timingNode.close().name()));
            return;
        }
        if ("/logbook".equals(route.resource)) {
            requireMethod(exchange, "GET");
            handleLogBook(exchange);
            return;
        }

        sendJson(
                exchange,
                404,
                MessageWriter.error("NOT_FOUND", "Unknown TimingNode resource"));
    }

    private void routeDevNode(HttpExchange exchange, String remainder) throws IOException {
        HttpRequestReader.NodeRoute route = requestReader.nodeRoute(remainder);
        if (route == null) {
            sendJson(
                    exchange,
                    404,
                    MessageWriter.error("NOT_FOUND", "Unknown IF-03 dev resource"));
            return;
        }
        requireCurrentNode(exchange, route.nodeId);

        if ("/auto-reg".equals(route.resource)) {
            requireMethod(exchange, "POST");
            handleAutoRegistration(exchange);
            return;
        }

        sendJson(
                exchange,
                404,
                MessageWriter.error("NOT_FOUND", "Unknown IF-03 dev resource"));
    }

    private void requireCurrentNode(HttpExchange exchange, String nodeId)
            throws IOException {
        if (timingNode.status().timingNodeId().value().equals(nodeId)) {
            return;
        }
        sendJson(
                exchange,
                404,
                MessageWriter.error(
                        "NODE_NOT_FOUND",
                        "Unknown TimingNode id"));
        throw ResponseAlreadySent.INSTANCE;
    }

    private void handleOpen(HttpExchange exchange) throws IOException {
        LocationId locationId = readLocationId(exchange);
        OpenResult result = timingNode.open(locationId);
        sendJson(
                exchange,
                200,
                MessageWriter.result(result.name()));
    }

    private LocationId readLocationId(HttpExchange exchange) throws IOException {
        int value = requestReader.readLocationIdRequest(exchange);
        try {
            return new LocationId(value);
        } catch (IllegalArgumentException ex) {
            throw HttpRequestReader.invalidValue(ex.getMessage());
        }
    }

    private void handleAutoRegistration(HttpExchange exchange) throws IOException {
        HttpRequestReader.AutoRegistrationRequest request =
                requestReader.readAutoRegistrationRequest(exchange);

        final RegistrationId registrationId;
        final TimingTimestamp time;
        try {
            registrationId = new RegistrationId(request.id);
            time = TimingTimestamp.parse(request.time);
        } catch (IllegalArgumentException ex) {
            throw HttpRequestReader.invalidValue(ex.getMessage());
        }

        if (!presentationGateway.capabilities().directRegistrationSimulationEnabled()) {
            sendJson(
                    exchange,
                    403,
                    MessageWriter.error(
                            "CAPABILITY_NOT_ENABLED",
                            "Direct registration simulation is not enabled"));
            return;
        }

        RegistrationResult result =
                timingNode.applyAutomaticRegistration(
                        AutomaticRegistrationAction.ADD,
                        registrationId,
                        time);
        if (result.outcome() == RegistrationResult.Outcome.NODE_NOT_OPEN) {
            sendJson(
                    exchange,
                    409,
                    MessageWriter.error(
                            "NODE_NOT_OPEN",
                            "Accepted registration requires an OPEN TimingNode"));
            return;
        }
        sendJson(
                exchange,
                200,
                MessageWriter.committedRegistration(
                        result.timingData()));
    }

    private void handleLogBook(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            sendJson(
                    exchange,
                    200,
                    MessageWriter.logBookInfo(timingNode.logBookCount()));
            return;
        }

        HttpRequestReader.LogBookQuery request = requestReader.readLogBookQuery(query);
        StringBuilder records = new StringBuilder();
        int count;
        if (request.last != null) {
            count = timingNode.visitLatestLogBook(
                    request.last.intValue(),
                    data -> MessageWriter.appendLogBookRecord(
                            records,
                            data,
                            timingDataCodec));
        } else {
            count = timingNode.visitLogBookFrom(
                    request.from.longValue(),
                    request.limit.intValue(),
                    data -> MessageWriter.appendLogBookRecord(
                            records,
                            data,
                            timingDataCodec));
        }

        Long next = null;
        if (request.from != null && request.from.longValue() <= count) {
            long lastReturned = Math.min(
                    (long) count,
                    request.from.longValue()
                            + request.limit.intValue()
                            - 1L);
            long candidate = lastReturned + 1L;
            if (candidate <= count) {
                next = Long.valueOf(candidate);
            }
        }

        sendJson(
                exchange,
                200,
                MessageWriter.logBookPage(
                        count,
                        next,
                        records));
    }

    private void sendOperationFailure(
            HttpExchange exchange,
            OperationException failure)
            throws IOException {
        switch (failure.reason()) {
            case BUSY:
                sendJson(exchange, 503, MessageWriter.error("BUSY", failure.getMessage()));
                return;
            case UNAVAILABLE:
                sendJson(exchange, 503, MessageWriter.error("UNAVAILABLE", failure.getMessage()));
                return;
            case TIMEOUT:
                sendJson(
                        exchange,
                        504,
                        MessageWriter.error("OUTCOME_UNKNOWN", failure.getMessage()));
                return;
            case INTERRUPTED:
                sendJson(exchange, 503, MessageWriter.error("INTERRUPTED", failure.getMessage()));
                return;
            case FAILED:
            default:
                sendJson(
                        exchange,
                        503,
                        MessageWriter.error("OPERATION_FAILED", failure.getMessage()));
        }
    }

    private static void requireMethod(HttpExchange exchange, String required)
            throws IOException {
        if (required.equals(exchange.getRequestMethod())) {
            return;
        }
        sendJson(
                exchange,
                405,
                MessageWriter.error(
                        "METHOD_NOT_ALLOWED",
                        "Expected " + required + " for this resource"));
        throw ResponseAlreadySent.INSTANCE;
    }

    private static void sendJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        } finally {
            exchange.close();
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Internal control-flow marker used only after a response was already sent.
     *
     * <p>It is deliberately not logged as an interface failure.</p>
     */
    private static final class ResponseAlreadySent extends RuntimeException {
        private static final ResponseAlreadySent INSTANCE = new ResponseAlreadySent();

        private ResponseAlreadySent() {
            super(null, null, false, false);
        }
    }
}
