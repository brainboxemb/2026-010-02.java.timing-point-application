package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class HttpRequestReaderTest {
    private final HttpRequestReader reader = new HttpRequestReader();

    @Test
    public void parsesNodeRouteAndSupportedLogBookQueries() {
        HttpRequestReader.NodeRoute route =
                reader.nodeRoute("timing-node-01/logbook");
        assertEquals("timing-node-01", route.nodeId);
        assertEquals("/logbook", route.resource);
        assertNull(reader.nodeRoute("timing-node-01"));
        assertNull(reader.nodeRoute("timing-node-01/"));

        HttpRequestReader.LogBookQuery range =
                reader.readLogBookQuery("from=2&limit=100");
        assertEquals(Long.valueOf(2L), range.from);
        assertEquals(Integer.valueOf(100), range.limit);
        assertNull(range.last);

        HttpRequestReader.LogBookQuery latest =
                reader.readLogBookQuery("last=10");
        assertNull(latest.from);
        assertNull(latest.limit);
        assertEquals(Integer.valueOf(10), latest.last);
    }

    @Test
    public void parsesSupportedJsonRequestBodies() {
        assertEquals(
                24,
                reader.parseLocationIdBody(bytes("{\"locationId\":24}")));

        HttpRequestReader.AutoRegistrationRequest request =
                reader.parseAutoRegistrationBody(bytes(
                        "{"
                                + "\"id\":\"N0001\","
                                + "\"time\":\"2026-10-01T12:00:00.000000000Z\""
                                + "}"));
        assertEquals("N0001", request.id);
        assertEquals("2026-10-01T12:00:00.000000000Z", request.time);
    }

    @Test
    public void parsesManualRegistrationAddRequest() {
        HttpRequestReader.ManualRegistrationRequest request =
                reader.parseManualRegistrationBody(bytes(
                        "{"
                                + "\"regId\":\"N0002\","
                                + "\"time\":\"2026-10-01T12:00:01.25Z\","
                                + "\"timeSource\":\"AUTO\""
                                + "}"));

        assertEquals("N0002", request.registrationId);
        assertEquals("2026-10-01T12:00:01.25Z", request.time);
        assertEquals("AUTO", request.timeSource);

        assertFailure(
                "INVALID_VALUE",
                "timeSource must be AUTO or MAN",
                () -> reader.parseManualRegistrationBody(bytes(
                        "{"
                                + "\"regId\":\"N0002\","
                                + "\"time\":\"2026-10-01T12:00:01.25Z\","
                                + "\"timeSource\":\"SERVER\""
                                + "}")));
    }

    @Test
    public void parsesAutomaticAndManualRegistrationRevokeRequests() {
        HttpRequestReader.RegistrationRevokeRequest automatic =
                reader.parseRegistrationRevokeBody(bytes(
                        "{"
                                + "\"recordType\":\"AUTO_REG\","
                                + "\"locationId\":24,"
                                + "\"regId\":\"N0001\","
                                + "\"time\":\"2026-10-01T12:00:00Z\""
                                + "}"));
        assertEquals("AUTO_REG", automatic.recordType);
        assertEquals(24, automatic.locationId);
        assertEquals("N0001", automatic.registrationId);
        assertEquals("2026-10-01T12:00:00Z", automatic.time);
        assertNull(automatic.timeSource);

        HttpRequestReader.RegistrationRevokeRequest manual =
                reader.parseRegistrationRevokeBody(bytes(
                        "{"
                                + "\"recordType\":\"MAN_REG\","
                                + "\"locationId\":24,"
                                + "\"regId\":\"N0002\","
                                + "\"time\":\"2026-10-01T12:00:01Z\","
                                + "\"timeSource\":\"MAN\""
                                + "}"));
        assertEquals("MAN_REG", manual.recordType);
        assertEquals("MAN", manual.timeSource);
    }

    @Test
    public void parsesTagProcessingSetAndClearRequests() {
        HttpRequestReader.TagProcessingUpdateRequest set =
                reader.parseTagProcessingUpdateBody(bytes(
                        "{"
                                + "\"action\":\"SET\","
                                + "\"value\":{"
                                + "\"quietTimeoutMillis\":300,"
                                + "\"sweepCadenceMillis\":75"
                                + "}"
                                + "}"));

        assertEquals(
                HttpRequestReader.TagProcessingUpdateRequest.Action.SET,
                set.action);
        assertEquals(
                Long.valueOf(300L),
                set.values.quietTimeoutMillis);
        assertNull(set.values.maxBurstDurationMillis);
        assertNull(set.values.duplicateWindowMillis);
        assertEquals(
                Long.valueOf(75L),
                set.values.sweepCadenceMillis);
        assertNull(set.values.observationQueueCapacity);

        HttpRequestReader.TagProcessingUpdateRequest clear =
                reader.parseTagProcessingUpdateBody(bytes(
                        "{\"action\":\"CLEAR\"}"));
        assertEquals(
                HttpRequestReader.TagProcessingUpdateRequest.Action.CLEAR,
                clear.action);
        assertNull(clear.values);
    }

    @Test
    public void rejectsInvalidRequestShapesWithStableCodes() {
        assertFailure(
                "INVALID_VALUE",
                "last cannot be combined",
                () -> reader.readLogBookQuery("last=1&limit=1"));
        assertFailure(
                "INVALID_VALUE",
                "limit must be <= 1000",
                () -> reader.readLogBookQuery("from=1&limit=1001"));
        assertFailure(
                "INVALID_VALUE",
                "Unsupported request field",
                () -> reader.parseLocationIdBody(bytes(
                        "{\"locationId\":24,\"extra\":1}")));
        assertFailure(
                "MALFORMED_REQUEST",
                "Malformed JSON request",
                () -> reader.parseAutoRegistrationBody(bytes("{not-json}")));
        assertFailure(
                "INVALID_VALUE",
                "AUTO_REG revoke must not contain timeSource",
                () -> reader.parseRegistrationRevokeBody(bytes(
                        "{"
                                + "\"recordType\":\"AUTO_REG\","
                                + "\"locationId\":24,"
                                + "\"regId\":\"N0001\","
                                + "\"time\":\"2026-10-01T12:00:00Z\","
                                + "\"timeSource\":\"AUTO\""
                                + "}")));
        assertFailure(
                "INVALID_VALUE",
                "MAN_REG revoke requires timeSource AUTO or MAN",
                () -> reader.parseRegistrationRevokeBody(bytes(
                        "{"
                                + "\"recordType\":\"MAN_REG\","
                                + "\"locationId\":24,"
                                + "\"regId\":\"N0001\","
                                + "\"time\":\"2026-10-01T12:00:00Z\""
                                + "}")));
        assertFailure(
                "INVALID_VALUE",
                "SET requires value",
                () -> reader.parseTagProcessingUpdateBody(bytes(
                        "{\"action\":\"SET\"}")));
        assertFailure(
                "INVALID_VALUE",
                "CLEAR does not accept value",
                () -> reader.parseTagProcessingUpdateBody(bytes(
                        "{"
                                + "\"action\":\"CLEAR\","
                                + "\"value\":{}"
                                + "}")));
        assertFailure(
                "INVALID_VALUE",
                "Unsupported tag-processing field",
                () -> reader.parseTagProcessingUpdateBody(bytes(
                        "{"
                                + "\"action\":\"SET\","
                                + "\"value\":{\"batchSize\":8}"
                                + "}")));
        assertFailure(
                "INVALID_VALUE",
                "must be a JSON integer",
                () -> reader.parseTagProcessingUpdateBody(bytes(
                        "{"
                                + "\"action\":\"SET\","
                                + "\"value\":{\"quietTimeoutMillis\":\"300\"}"
                                + "}")));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertFailure(
            String code,
            String messagePart,
            Runnable operation) {
        try {
            operation.run();
            fail("Expected request parsing failure");
        } catch (HttpRequestReader.RequestException ex) {
            assertEquals(code, ex.code());
            if (!ex.getMessage().contains(messagePart)) {
                fail("Unexpected message: " + ex.getMessage());
            }
        }
    }
}
