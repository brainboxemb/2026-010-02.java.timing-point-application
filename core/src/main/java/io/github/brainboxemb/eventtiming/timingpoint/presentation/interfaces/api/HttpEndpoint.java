package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingPatch;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.SimulationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy.AutomaticRegistrationAction;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy.RegistrationRecordType;
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
    private final ConfigurationControl configuration;
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
        this.configuration = presentationGateway.configuration();
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
            sendJson(
                    exchange,
                    200,
                    MessageWriter.status(
                            presentationGateway
                                    .timingNodeStatuses()));
            return;
        }
        if ("/api/v1/configuration".equals(path)) {
            requireMethod(exchange, "GET");
            sendJson(
                    exchange,
                    200,
                    MessageWriter.configuration(
                            configuration.snapshot()));
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
        TimingNodeProxy timingNode =
                requireNode(
                        exchange,
                        route.nodeId);

        if ("/open".equals(route.resource)) {
            requireMethod(exchange, "POST");
            handleOpen(exchange, timingNode);
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
        if ("/registration/manual".equals(route.resource)) {
            requireMethod(exchange, "POST");
            handleManualRegistration(exchange, timingNode);
            return;
        }
        if ("/registration/revoke".equals(route.resource)) {
            requireMethod(exchange, "POST");
            handleRegistrationRevoke(exchange, timingNode);
            return;
        }
        if ("/logbook".equals(route.resource)) {
            requireMethod(exchange, "GET");
            handleLogBook(exchange, timingNode);
            return;
        }
        if ("/configuration/tag-processing".equals(
                route.resource)) {
            requireMethod(exchange, "POST");
            handleTagProcessingConfiguration(exchange, timingNode);
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
        TimingNodeProxy timingNode =
                requireNode(
                        exchange,
                        route.nodeId);

        if ("/auto-reg".equals(route.resource)) {
            requireMethod(exchange, "POST");
            handleAutoRegistration(exchange, timingNode);
            return;
        }
        if ("/simulation/registration".equals(
                route.resource)) {
            requireMethod(exchange, "POST");
            handleSimulationRegistration(exchange);
            return;
        }

        sendJson(
                exchange,
                404,
                MessageWriter.error("NOT_FOUND", "Unknown IF-03 dev resource"));
    }

    private TimingNodeProxy requireNode(
            HttpExchange exchange,
            String nodeId)
            throws IOException {
        try {
            return presentationGateway.timingNode(
                    new NodeId(nodeId));
        } catch (IllegalArgumentException ex) {
            sendJson(
                    exchange,
                    404,
                    MessageWriter.error(
                            "NODE_NOT_FOUND",
                            "Unknown TimingNode id"));
            throw ResponseAlreadySent.INSTANCE;
        }
    }

    private void handleOpen(
            HttpExchange exchange,
            TimingNodeProxy timingNode)
            throws IOException {
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

    private void handleSimulationRegistration(
            HttpExchange exchange)
            throws IOException {
        if (!presentationGateway
                .capabilities()
                .tagScenarioSimulationEnabled()) {
            sendJson(
                    exchange,
                    403,
                    MessageWriter.error(
                            "CAPABILITY_NOT_ENABLED",
                            "Tag scenario simulation is not enabled"));
            return;
        }

        HttpRequestReader.SimulationRegistrationRequest request =
                requestReader.readSimulationRegistrationRequest(
                        exchange);

        final RegistrationId registrationId;
        try {
            registrationId =
                    new RegistrationId(
                            request.registrationId);
        } catch (IllegalArgumentException ex) {
            throw HttpRequestReader.invalidValue(
                    ex.getMessage());
        }

        SimulationControl.StartResult result =
                presentationGateway
                        .simulation()
                        .startRegistration(
                                registrationId,
                                request.profile);

        switch (result) {
            case ACCEPTED:
                sendJson(
                        exchange,
                        200,
                        MessageWriter.result(
                                result.name()));
                return;
            case UNKNOWN_REGISTRATION:
                sendJson(
                        exchange,
                        400,
                        MessageWriter.error(
                                "UNKNOWN_REGISTRATION",
                                "RegistrationId is not present in EventData"));
                return;
            case UNKNOWN_PROFILE:
                sendJson(
                        exchange,
                        400,
                        MessageWriter.error(
                                "UNKNOWN_SIMULATION_PROFILE",
                                "Unknown simulated tag profile"));
                return;
            case UNAVAILABLE:
                sendJson(
                        exchange,
                        409,
                        MessageWriter.error(
                                "SIMULATION_UNAVAILABLE",
                                "Simulated antenna is not ready for inventory observations"));
                return;
            case BUSY:
                sendJson(
                        exchange,
                        503,
                        MessageWriter.error(
                                "SIMULATION_BUSY",
                                "Simulated tag scenario capacity is busy"));
                return;
            default:
                throw new IllegalStateException(
                        "Unhandled simulation result "
                                + result);
        }
    }

    private void handleAutoRegistration(
            HttpExchange exchange,
            TimingNodeProxy timingNode)
            throws IOException {
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

    private void handleManualRegistration(
            HttpExchange exchange,
            TimingNodeProxy timingNode)
            throws IOException {
        HttpRequestReader.ManualRegistrationRequest request =
                requestReader.readManualRegistrationRequest(
                        exchange);

        final RegistrationId registrationId;
        final TimingTimestamp time;
        final ManualTimeSource timeSource;
        try {
            registrationId =
                    new RegistrationId(
                            request.registrationId);
            time =
                    TimingTimestamp.parse(
                            request.time);
            timeSource =
                    "AUTO".equals(request.timeSource)
                            ? ManualTimeSource.AUTOMATIC
                            : ManualTimeSource.OPERATOR_ENTERED;
        } catch (IllegalArgumentException ex) {
            throw HttpRequestReader.invalidValue(
                    ex.getMessage());
        }

        RegistrationResult result =
                timingNode.addManualRegistration(
                        registrationId,
                        time,
                        timeSource);
        if (result.outcome()
                == RegistrationResult.Outcome.NODE_NOT_OPEN) {
            sendJson(
                    exchange,
                    409,
                    MessageWriter.error(
                            "NODE_NOT_OPEN",
                            "Manual registration requires an OPEN TimingNode"));
            return;
        }
        sendJson(
                exchange,
                200,
                MessageWriter.committedRegistration(
                        result.timingData()));
    }

    private void handleRegistrationRevoke(
            HttpExchange exchange,
            TimingNodeProxy timingNode)
            throws IOException {
        HttpRequestReader.RegistrationRevokeRequest request =
                requestReader.readRegistrationRevokeRequest(
                        exchange);

        final RegistrationRecordType recordType;
        final LocationId locationId;
        final RegistrationId registrationId;
        final TimingTimestamp time;
        final ManualTimeSource timeSource;
        try {
            recordType =
                    RegistrationRecordType.valueOf(
                            request.recordType);
            locationId =
                    new LocationId(
                            request.locationId);
            registrationId =
                    new RegistrationId(
                            request.registrationId);
            time =
                    TimingTimestamp.parse(
                            request.time);
            if (request.timeSource == null) {
                timeSource = null;
            } else if ("AUTO".equals(request.timeSource)) {
                timeSource =
                        ManualTimeSource.AUTOMATIC;
            } else if ("MAN".equals(request.timeSource)) {
                timeSource =
                        ManualTimeSource.OPERATOR_ENTERED;
            } else {
                throw new IllegalArgumentException(
                        "timeSource must be AUTO or MAN");
            }
        } catch (IllegalArgumentException ex) {
            throw HttpRequestReader.invalidValue(
                    ex.getMessage());
        }

        RegistrationResult result =
                timingNode.revokeRegistration(
                        recordType,
                        locationId,
                        registrationId,
                        time,
                        timeSource);
        sendJson(
                exchange,
                200,
                MessageWriter.committedRegistration(
                        result.timingData()));
    }

    private void handleTagProcessingConfiguration(
            HttpExchange exchange,
            TimingNodeProxy timingNode)
            throws IOException {
        HttpRequestReader.TagProcessingUpdateRequest request =
                requestReader.readTagProcessingUpdateRequest(
                        exchange);

        NodeId nodeId =
                timingNode.timingNodeId();
        ConfigurationControl.Update update;

        if (request.action
                == HttpRequestReader
                        .TagProcessingUpdateRequest
                        .Action.CLEAR) {
            update =
                    configuration.clearTagProcessing(nodeId);
        } else {
            HttpRequestReader.TagProcessingValues values =
                    request.values;
            update =
                    configuration.setTagProcessing(
                            nodeId,
                            new TagProcessingPatch(
                                    values.quietTimeoutMillis,
                                    values.maxBurstDurationMillis,
                                    values.duplicateWindowMillis,
                                    values.sweepCadenceMillis,
                                    values.observationQueueCapacity));
        }

        int status;
        switch (update.result()) {
            case APPLIED:
            case NO_CHANGE:
                status = 200;
                break;
            case INVALID:
                status = 400;
                break;
            case RESTART_REQUIRED:
                status = 409;
                break;
            default:
                throw new IllegalStateException(
                        "Unsupported configuration result "
                                + update.result());
        }

        sendJson(
                exchange,
                status,
                MessageWriter.configurationUpdate(update));
    }

    private void handleLogBook(
            HttpExchange exchange,
            TimingNodeProxy timingNode)
            throws IOException {
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
