package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.SimulationControl;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HttpEndpointTest {
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void exposesVersionAndStatusOverRealHttp() throws Exception {
        PresentationGatewayFixture fixture = new PresentationGatewayFixture(identity());
        HttpEndpoint server = new HttpEndpoint("127.0.0.1", 0, fixture.handler());
        server.start();

        try {
            Response version = request(server.boundPort(), "GET", "/api/v1/version", null);
            assertEquals(200, version.status);
            assertTrue(version.contentType.startsWith("application/json"));
            assertTrue(version.body.contains("\"application\":\"event-timing-app\""));
            assertTrue(version.body.contains("\"version\":\"test-version\""));
            assertTrue(version.body.contains("\"sourceRef\":\"feature/test\""));
            assertTrue(version.body.contains("\"dirty\":false"));
            assertTrue(version.body.contains("\"apiVersion\":\"1\""));

            Response status = request(server.boundPort(), "GET", "/api/v1/status", null);
            assertEquals(200, status.status);
            assertTrue(status.body.contains("\"nodes\":[{"));
            assertTrue(status.body.contains("\"id\":\"A\""));
            assertTrue(status.body.contains("\"locationId\":null"));
            assertTrue(status.body.contains("\"state\":\"CLOSED\""));
            assertTrue(status.body.contains("\"problems\":[]"));
            assertTrue(!status.body.contains("\"build\":"));
            assertTrue(!status.body.contains("\"apiVersion\":"));
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void routesNodeCommandsAndStatusAcrossMultipleTimingNodes()
            throws Exception {
        TimingNode first =
                TimingNodeFixture.create(
                        new NodeId("A"),
                        new MemoryStore(),
                        () -> RECORDED_AT.instant());
        TimingNode second =
                TimingNodeFixture.create(
                        new NodeId("B"),
                        new MemoryStore(),
                        () -> RECORDED_AT.instant());
        PresentationGateway gateway =
                new PresentationGateway(
                        identity(),
                        nodes(first, second),
                        PresentationGatewayFixture.configurationControl(
                                new NodeId("A"),
                                new NodeId("B")));

        first.activate();
        second.activate();
        HttpEndpoint server =
                new HttpEndpoint(
                        "127.0.0.1",
                        0,
                        gateway);
        server.start();

        try {
            Response opened =
                    request(
                            server.boundPort(),
                            "POST",
                            "/api/v1/node/B/open",
                            "{\"locationId\":24}");
            assertEquals(200, opened.status);
            assertTrue(
                    opened.body.contains(
                            "\"result\":\"OPENED\""));

            Response status =
                    request(
                            server.boundPort(),
                            "GET",
                            "/api/v1/status",
                            null);
            assertEquals(200, status.status);
            assertTrue(status.body.contains(
                    "{\"id\":\"A\",\"locationId\":null,\"state\":\"CLOSED\"}"));
            assertTrue(status.body.contains(
                    "{\"id\":\"B\",\"locationId\":24,\"state\":\"OPEN\"}"));
        } finally {
            server.close();
            second.deactivate();
            first.deactivate();
        }
    }

    @Test
    public void controlsAndObservesFirstRegistrationThroughPublicHttp() throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();
        HttpEndpoint server = new HttpEndpoint("127.0.0.1", 0, fixture.handler);
        server.start();

        try {
            Response capabilities =
                    request(server.boundPort(), "GET", "/api/v1/capabilities", null);
            assertEquals(200, capabilities.status);
            assertTrue(capabilities.body.contains(
                    "\"id\":\"DIRECT_REGISTRATION_SIMULATION\""));
            assertTrue(capabilities.body.contains(
                    "\"id\":\"TAG_SCENARIO_SIMULATION\""));
            assertTrue(capabilities.body.contains("\"supported\":true"));
            assertTrue(capabilities.body.contains(
                    "\"id\":\"TAG_SCENARIO_SIMULATION\",\"supported\":true,\"enabled\":false"));

            Response openWithoutLocation =
                    request(server.boundPort(), "POST", "/api/v1/node/A/open", null);
            assertEquals(400, openWithoutLocation.status);
            assertTrue(openWithoutLocation.body.contains("\"code\":\"MALFORMED_REQUEST\""));

            Response obsoleteLocationResource = request(
                    server.boundPort(),
                    "PUT",
                    "/api/v1/node/A/location",
                    "{\"locationId\":23}");
            assertEquals(404, obsoleteLocationResource.status);
            assertTrue(obsoleteLocationResource.body.contains("\"code\":\"NOT_FOUND\""));

            Response opened =
                    request(
                            server.boundPort(),
                            "POST",
                            "/api/v1/node/A/open",
                            "{\"locationId\":24}");
            assertEquals(200, opened.status);
            assertTrue(opened.body.contains("\"result\":\"OPENED\""));

            Response openedStatus =
                    request(server.boundPort(), "GET", "/api/v1/status", null);
            assertTrue(openedStatus.body.contains("\"locationId\":24"));
            assertTrue(openedStatus.body.contains("\"state\":\"OPEN\""));

            Response repeatedOpen =
                    request(
                            server.boundPort(),
                            "POST",
                            "/api/v1/node/A/open",
                            "{\"locationId\":25}");
            assertEquals(200, repeatedOpen.status);
            assertTrue(repeatedOpen.body.contains("\"result\":\"ALREADY_OPEN\""));

            Response stillOpenStatus =
                    request(server.boundPort(), "GET", "/api/v1/status", null);
            assertTrue(stillOpenStatus.body.contains("\"locationId\":24"));

            Response registration = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/dev/node/A/auto-reg",
                    "{"
                            + "\"id\":\"N0001\","
                            + "\"time\":"
                            + "\"2026-10-01T12:00:00.000000000Z\""
                            + "}");
            assertEquals(200, registration.status);
            assertEquals("{\"seq\":2}", registration.body);

            Response logBookInfo = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/node/A/logbook",
                    null);
            assertEquals(200, logBookInfo.status);
            assertEquals("{\"count\":2,\"first\":1,\"last\":2}", logBookInfo.body);

            Response history = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/node/A/logbook?from=1&limit=100",
                    null);
            assertEquals(200, history.status);
            assertTrue(history.body.contains("\"count\":2"));
            assertTrue(history.body.contains("\"next\":null"));
            assertTrue(history.body.contains("\"seqNr\":1"));
            assertTrue(history.body.contains("\"recType\":\"NODE_INFO\""));
            assertTrue(history.body.contains("\"code\":[\"OPEN\"]"));
            assertTrue(history.body.contains("\"seqNr\":2"));
            assertTrue(history.body.contains("\"locId\":24"));
            assertTrue(history.body.contains("\"recType\":\"AUTO_REG\""));
            assertTrue(history.body.contains("\"regId\":\"N0001\""));
            assertTrue(history.body.contains("\"code\":[\"ADD\"]"));
            assertTrue(history.body.contains(
                    "\"time\":\"2026-10-01T12:00:00.00Z\""));

            Response latest = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/node/A/logbook?last=1",
                    null);
            assertEquals(200, latest.status);
            assertTrue(latest.body.contains("\"seqNr\":2"));

            Response revoked = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/registration/revoke",
                    "{"
                            + "\"recordType\":\"AUTO_REG\","
                            + "\"locationId\":24,"
                            + "\"regId\":\"N0001\","
                            + "\"time\":\"2026-10-01T12:00:00.000000000Z\""
                            + "}");
            assertEquals(200, revoked.status);
            assertEquals("{\"seq\":3}", revoked.body);

            Response revokedRecord = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/node/A/logbook?last=1",
                    null);
            assertEquals(200, revokedRecord.status);
            assertTrue(revokedRecord.body.contains("\"seqNr\":3"));
            assertTrue(revokedRecord.body.contains("\"recType\":\"AUTO_REG\""));
            assertTrue(revokedRecord.body.contains("\"regId\":\"N0001\""));
            assertTrue(revokedRecord.body.contains("\"code\":[\"REV\"]"));

            Response wrongNode = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/node/other-node/logbook",
                    null);
            assertEquals(404, wrongNode.status);
            assertTrue(wrongNode.body.contains("\"code\":\"NODE_NOT_FOUND\""));

            Response closed =
                    request(server.boundPort(), "POST", "/api/v1/node/A/close", null);
            assertEquals(200, closed.status);
            assertTrue(closed.body.contains("\"result\":\"CLOSED\""));
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void startsSimulatedTagPassageThroughCapabilityGatedControl()
            throws Exception {
        AtomicReference<RegistrationId> registration =
                new AtomicReference<RegistrationId>();
        AtomicReference<String> profile =
                new AtomicReference<String>();
        SimulationControl simulation =
                (registrationId, profileId) -> {
                    registration.set(
                            registrationId);
                    profile.set(
                            profileId);
                    return SimulationControl.StartResult.ACCEPTED;
                };

        Fixture fixture =
                new Fixture(
                        simulation);
        fixture.start();
        HttpEndpoint server =
                new HttpEndpoint(
                        "127.0.0.1",
                        0,
                        fixture.handler);
        server.start();

        try {
            Response capabilities =
                    request(
                            server.boundPort(),
                            "GET",
                            "/api/v1/capabilities",
                            null);
            assertEquals(
                    200,
                    capabilities.status);
            assertTrue(
                    capabilities.body.contains(
                            "\"id\":\"TAG_SCENARIO_SIMULATION\",\"supported\":true,\"enabled\":true"));

            Response accepted =
                    request(
                            server.boundPort(),
                            "POST",
                            "/api/v1/dev/node/A/simulation/registration",
                            "{"
                                    + "\"regId\":\"N0042\","
                                    + "\"profile\":\"normal\""
                                    + "}");
            assertEquals(
                    200,
                    accepted.status);
            assertEquals(
                    "{\"result\":\"ACCEPTED\"}",
                    accepted.body);
            assertEquals(
                    new RegistrationId("N0042"),
                    registration.get());
            assertEquals(
                    "normal",
                    profile.get());
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void commitsManualRegistrationThroughNormalHttp() throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();
        HttpEndpoint server =
                new HttpEndpoint(
                        "127.0.0.1",
                        0,
                        fixture.handler);
        server.start();

        try {
            Response open = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/open",
                    "{\"locationId\":24}");
            assertEquals(200, open.status);

            Response automaticTime = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/registration/manual",
                    "{"
                            + "\"regId\":\"N0002\","
                            + "\"time\":\"2026-10-01T12:00:01.25Z\","
                            + "\"timeSource\":\"AUTO\""
                            + "}");
            assertEquals(200, automaticTime.status);
            assertEquals("{\"seq\":2}", automaticTime.body);

            Response enteredTime = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/registration/manual",
                    "{"
                            + "\"regId\":\"N0003\","
                            + "\"time\":\"2026-10-01T11:59:58.75Z\","
                            + "\"timeSource\":\"MAN\""
                            + "}");
            assertEquals(200, enteredTime.status);
            assertEquals("{\"seq\":3}", enteredTime.body);

            Response history = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/node/A/logbook?from=2&limit=2",
                    null);
            assertEquals(200, history.status);
            assertTrue(history.body.contains("\"recType\":\"MAN_REG\""));
            assertTrue(history.body.contains("\"regId\":\"N0002\""));
            assertTrue(history.body.contains("\"code\":[\"ADD\",\"AUTO\"]"));
            assertTrue(history.body.contains(
                    "\"time\":\"2026-10-01T12:00:01.25Z\""));
            assertTrue(history.body.contains("\"regId\":\"N0003\""));
            assertTrue(history.body.contains("\"code\":[\"ADD\",\"MAN\"]"));
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void queriesSetsAndClearsRuntimeConfigurationThroughPublicHttp()
            throws Exception {
        PresentationGatewayFixture fixture =
                new PresentationGatewayFixture(identity());
        HttpEndpoint server =
                new HttpEndpoint(
                        "127.0.0.1",
                        0,
                        fixture.handler());
        server.start();

        try {
            Response initial = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/configuration",
                    null);
            assertEquals(200, initial.status);
            assertTrue(initial.body.contains("\"id\":\"A\""));
            assertTrue(initial.body.contains(
                    "\"quietTimeoutMillis\":250"));
            assertTrue(initial.body.contains(
                    "\"observationQueueCapacity\":256"));
            assertTrue(initial.body.contains(
                    "\"overridden\":false"));
            assertTrue(initial.body.contains(
                    "\"observationQueueCapacity\":false"));

            Response applied = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/configuration/tag-processing",
                    "{"
                            + "\"action\":\"SET\","
                            + "\"value\":{"
                            + "\"quietTimeoutMillis\":300,"
                            + "\"sweepCadenceMillis\":75"
                            + "}"
                            + "}");
            assertEquals(200, applied.status);
            assertTrue(applied.body.contains(
                    "\"result\":\"APPLIED\""));
            assertTrue(applied.body.contains(
                    "\"quietTimeoutMillis\":300"));
            assertTrue(applied.body.contains(
                    "\"sweepCadenceMillis\":75"));
            assertTrue(applied.body.contains(
                    "\"overridden\":true"));

            Response current = request(
                    server.boundPort(),
                    "GET",
                    "/api/v1/configuration",
                    null);
            assertEquals(200, current.status);
            assertTrue(current.body.contains(
                    "\"quietTimeoutMillis\":300"));
            assertTrue(current.body.contains(
                    "\"sweepCadenceMillis\":75"));
            assertTrue(current.body.contains(
                    "\"overridden\":true"));

            Response noChange = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/configuration/tag-processing",
                    "{"
                            + "\"action\":\"SET\","
                            + "\"value\":{"
                            + "\"quietTimeoutMillis\":300"
                            + "}"
                            + "}");
            assertEquals(200, noChange.status);
            assertTrue(noChange.body.contains(
                    "\"result\":\"NO_CHANGE\""));

            Response restartRequired = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/configuration/tag-processing",
                    "{"
                            + "\"action\":\"SET\","
                            + "\"value\":{"
                            + "\"observationQueueCapacity\":128"
                            + "}"
                            + "}");
            assertEquals(409, restartRequired.status);
            assertTrue(restartRequired.body.contains(
                    "\"result\":\"RESTART_REQUIRED\""));
            assertTrue(restartRequired.body.contains(
                    "\"observationQueueCapacity\":256"));
            assertTrue(restartRequired.body.contains(
                    "\"quietTimeoutMillis\":300"));

            Response invalid = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/configuration/tag-processing",
                    "{"
                            + "\"action\":\"SET\","
                            + "\"value\":{"
                            + "\"quietTimeoutMillis\":0"
                            + "}"
                            + "}");
            assertEquals(400, invalid.status);
            assertTrue(invalid.body.contains(
                    "\"result\":\"INVALID\""));
            assertTrue(invalid.body.contains(
                    "\"quietTimeoutMillis\":300"));

            Response cleared = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/configuration/tag-processing",
                    "{\"action\":\"CLEAR\"}");
            assertEquals(200, cleared.status);
            assertTrue(cleared.body.contains(
                    "\"result\":\"APPLIED\""));
            assertTrue(cleared.body.contains(
                    "\"quietTimeoutMillis\":250"));
            assertTrue(cleared.body.contains(
                    "\"sweepCadenceMillis\":50"));
            assertTrue(cleared.body.contains(
                    "\"overridden\":false"));

            Response wrongNode = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/B/configuration/tag-processing",
                    "{\"action\":\"CLEAR\"}");
            assertEquals(404, wrongNode.status);
            assertTrue(wrongNode.body.contains(
                    "\"code\":\"NODE_NOT_FOUND\""));
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void exposesContainedRecoveryFailureAndRejectsNormalNodeOperation()
            throws Exception {
        TimingNode node = TimingNodeFixture.create(
                new NodeId("A"),
                new FailingRecoveryStore(),
                () -> RECORDED_AT.instant());
        PresentationGateway handler =
                new PresentationGateway(
                        identity(),
                        nodes(node),
                        PresentationGatewayFixture.configurationControl(
                                new NodeId("A")));
        node.activate();
        HttpEndpoint server = new HttpEndpoint("127.0.0.1", 0, handler);
        server.start();

        try {
            Response status =
                    request(server.boundPort(), "GET", "/api/v1/status", null);
            assertEquals(200, status.status);
            assertTrue(status.body.contains("\"id\":\"A\""));
            assertTrue(status.body.contains("\"locationId\":null"));
            assertTrue(status.body.contains("\"state\":\"ERROR\""));
            assertTrue(status.body.contains(
                    "\"code\":\"TIMING_DATA_RECOVERY_FAILED\""));
            assertTrue(status.body.contains("\"severity\":\"ERROR\""));
            assertTrue(status.body.contains("\"nodeId\":\"A\""));
            assertTrue(status.body.contains("expected recovery failure"));

            Response open = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/open",
                    "{\"locationId\":24}");
            assertEquals(503, open.status);
            assertTrue(open.body.contains("\"code\":\"OPERATION_FAILED\""));
            assertTrue(open.body.contains("expected recovery failure"));

            Response unchanged =
                    request(server.boundPort(), "GET", "/api/v1/status", null);
            assertTrue(unchanged.body.contains("\"state\":\"ERROR\""));
        } finally {
            server.close();
            node.deactivate();
        }
    }

    @Test
    public void mapsRequestAndMethodFailuresToStableJsonErrors() throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();
        HttpEndpoint server = new HttpEndpoint("127.0.0.1", 0, fixture.handler);
        server.start();

        try {
            Response missing =
                    request(server.boundPort(), "GET", "/api/v1/missing", null);
            assertEquals(404, missing.status);
            assertTrue(missing.body.contains("\"code\":\"NOT_FOUND\""));

            Response method =
                    request(server.boundPort(), "POST", "/api/v1/status", null);
            assertEquals(405, method.status);
            assertTrue(method.body.contains("\"code\":\"METHOD_NOT_ALLOWED\""));
            assertTrue(!method.body.contains("INTERNAL_ERROR"));

            Response malformed = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/open",
                    "{not-json}");
            assertEquals(400, malformed.status);
            assertTrue(malformed.body.contains("\"code\":\"MALFORMED_REQUEST\""));

            Response invalid = request(
                    server.boundPort(),
                    "POST",
                    "/api/v1/node/A/open",
                    "{\"locationId\":0}");
            assertEquals(400, invalid.status);
            assertTrue(invalid.body.contains("\"code\":\"INVALID_VALUE\""));
        } finally {
            server.close();
            fixture.close();
        }
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "test-version",
                "abc123def456",
                "feature/test",
                "local",
                false);
    }

    private static Response request(
            int port,
            String method,
            String path,
            String body)
            throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("http://127.0.0.1:" + port + path)
                        .openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept", "application/json");

        if (body != null) {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
        }

        int status = connection.getResponseCode();
        InputStream stream =
                status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseBody = readAll(stream);
        String contentType = connection.getHeaderField("Content-Type");
        connection.disconnect();
        return new Response(status, contentType, responseBody);
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private static TimingNodeList nodes(
            TimingNode... timingNodes) {
        TimingNodeList result =
                new TimingNodeList();
        for (TimingNode timingNode : timingNodes) {
            result.add(timingNode);
        }
        return result;
    }

    private static final class Fixture implements AutoCloseable {
        private final TimingNode node;
        private final PresentationGateway handler;

        private Fixture() {
            this(
                    null);
        }

        private Fixture(
                SimulationControl simulation) {
            node = TimingNodeFixture.create(
                    new NodeId("A"),
                    new MemoryStore(),
                    () -> RECORDED_AT.instant());
            handler =
                    new PresentationGateway(
                            identity(),
                            nodes(node),
                            PresentationGatewayFixture.configurationControl(
                                    new NodeId("A")),
                            simulation);
        }

        private void start() {
            node.activate();
        }

        @Override
        public void close() {
            node.deactivate();
        }
    }

    private static final class FailingRecoveryStore
            implements TimingDataPersistence {
        @Override
        public LoadResult load() throws PersistenceException {
            throw new PersistenceException("expected recovery failure");
        }

        @Override
        public void append(TimingData data) {
            throw new AssertionError("ERROR TimingNode must not append TimingData");
        }
    }

    private static final class MemoryStore implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(Collections.<TimingData>emptyList(), false);
        }

        @Override
        public void append(TimingData data) {
        }
    }

    private static final class Response {
        private final int status;
        private final String contentType;
        private final String body;

        private Response(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
