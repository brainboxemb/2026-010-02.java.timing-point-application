package io.github.brainboxemb.eventtiming.systemtest;

import io.github.brainboxemb.eventtiming.systemtest.TimingApplicationFixture.Ports;
import io.github.brainboxemb.eventtiming.systemtest.framework.EventStream;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient.Response;
import io.github.brainboxemb.eventtiming.systemtest.framework.ProcessRun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * VC-ST1-005 — lifecycle TimingData source ordering and recovery.
 */
public class VcSt1_005Test {
    private static final String NODE_ID = "A";
    private static final String REGISTRATION_ID = "N0005";
    private static final String REGISTRATION_TIME = "2026-10-01T12:05:00Z";
    private static final String EXPECTED_WIRE_TIME = "2026-10-01T12:05:00.00Z";

    @Test
    public void verifiesLifecycleOrderingIdempotenceAndSequenceContinuity()
            throws Exception {
        TimingApplicationFixture fixture =
                TimingApplicationFixture.create("VC-ST1-005", NODE_ID);
        BlackBoxEvidence evidence = fixture.evidence();

        ProcessRun firstRun = null;
        ProcessRun secondRun = null;
        boolean passed = false;
        Throwable evidenceFailure = null;

        try {
            Ports firstPorts = fixture.reservePorts();
            firstRun = fixture.start(
                    fixture.writeConfiguration(
                            "application-run-1.yml",
                            firstPorts),
                    "vc-st1-005-run-1-output");
            verifyInitialHistory(
                    fixture,
                    firstRun,
                    firstPorts);

            Ports secondPorts = fixture.reservePorts();
            secondRun = fixture.start(
                    fixture.writeConfiguration(
                            "application-run-2.yml",
                            secondPorts),
                    "vc-st1-005-run-2-output");
            verifyRecoveredContinuation(
                    fixture,
                    secondRun,
                    secondPorts);

            evidence.verifyRuntimeLogging(
                    combinedOutput(firstRun, secondRun));
            passed = true;
        } catch (Throwable failure) {
            evidenceFailure = failure;
            throw new AssertionError(
                    "VC-ST1-005 black-box verification failed. Process output:\n"
                            + combinedOutput(firstRun, secondRun),
                    failure);
        } finally {
            fixture.cleanup(firstRun);
            fixture.cleanup(secondRun);
            evidence.writeProcessOutput(
                    combinedOutput(firstRun, secondRun));
            evidence.writeResult(
                    passed,
                    evidenceFailure);
        }
    }

    private static void verifyInitialHistory(
            TimingApplicationFixture fixture,
            ProcessRun run,
            Ports ports)
            throws Exception {
        fixture.awaitReady(run, ports);
        HttpTestClient http = fixture.http(ports);
        EventStream events = fixture.connectEvents(ports);
        try {
            assertContains(
                    events.awaitEvent("STATUS_SNAPSHOT"),
                    "\"state\":\"CLOSED\"");

            Response open = http.post(
                    nodePath("/open"),
                    "{\"locationId\":24}");
            assertEquals(200, open.status());
            assertContains(open.body(), "\"result\":\"OPENED\"");
            assertLifecycle(
                    events.awaitEvent("TIMING_DATA_COMMITTED"),
                    1L,
                    24,
                    "OPEN");
            assertContains(
                    events.awaitEvent("STATUS_CHANGED"),
                    "\"state\":\"OPEN\"");

            Response alreadyOpen = http.post(
                    nodePath("/open"),
                    "{\"locationId\":24}");
            assertEquals(200, alreadyOpen.status());
            assertContains(
                    alreadyOpen.body(),
                    "\"result\":\"ALREADY_OPEN\"");
            events.assertNoEvent(
                    "TIMING_DATA_COMMITTED",
                    300L);
            assertLogBookCount(
                    http,
                    1,
                    1L);

            Response registration = http.post(
                    "/api/v1/dev/node/" + NODE_ID + "/auto-reg",
                    "{"
                            + "\"id\":\"" + REGISTRATION_ID + "\","
                            + "\"time\":\"" + REGISTRATION_TIME + "\""
                            + "}");
            assertEquals(200, registration.status());
            assertContains(registration.body(), "\"seq\":2");
            assertRegistration(
                    events.awaitEvent("TIMING_DATA_COMMITTED"),
                    2L);

            Response close = http.post(
                    nodePath("/close"),
                    "");
            assertEquals(200, close.status());
            assertContains(close.body(), "\"result\":\"CLOSED\"");
            assertLifecycle(
                    events.awaitEvent("TIMING_DATA_COMMITTED"),
                    3L,
                    24,
                    "CLOSE");
            assertContains(
                    events.awaitEvent("STATUS_CHANGED"),
                    "\"state\":\"CLOSED\"");

            Response alreadyClosed = http.post(
                    nodePath("/close"),
                    "");
            assertEquals(200, alreadyClosed.status());
            assertContains(
                    alreadyClosed.body(),
                    "\"result\":\"ALREADY_CLOSED\"");
            events.assertNoEvent(
                    "TIMING_DATA_COMMITTED",
                    300L);

            assertLogBookHistory(
                    http,
                    3,
                    3L,
                    24);
        } finally {
            events.closeQuietly();
        }

        fixture.shutdown(
                run,
                ports);
    }

    private static void verifyRecoveredContinuation(
            TimingApplicationFixture fixture,
            ProcessRun run,
            Ports ports)
            throws Exception {
        fixture.awaitReady(run, ports);
        HttpTestClient http = fixture.http(ports);
        EventStream events = fixture.connectEvents(ports);
        try {
            String snapshot =
                    events.awaitEvent("STATUS_SNAPSHOT");
            assertContains(snapshot, "\"state\":\"CLOSED\"");
            assertContains(snapshot, "\"locationId\":null");
            events.assertNoEvent(
                    "TIMING_DATA_COMMITTED",
                    500L);

            assertLogBookHistory(
                    http,
                    3,
                    3L,
                    24);

            Response open = http.post(
                    nodePath("/open"),
                    "{\"locationId\":25}");
            assertEquals(200, open.status());
            assertContains(open.body(), "\"result\":\"OPENED\"");
            assertLifecycle(
                    events.awaitEvent("TIMING_DATA_COMMITTED"),
                    4L,
                    25,
                    "OPEN");
            String status =
                    events.awaitEvent("STATUS_CHANGED");
            assertContains(status, "\"state\":\"OPEN\"");
            assertContains(status, "\"locationId\":25");

            assertLogBookCount(
                    http,
                    4,
                    4L);
        } finally {
            events.closeQuietly();
        }

        fixture.shutdown(
                run,
                ports);
    }

    private static void assertLogBookHistory(
            HttpTestClient http,
            int count,
            long last,
            int locationId)
            throws Exception {
        assertLogBookCount(
                http,
                count,
                last);

        Response page = http.get(
                nodePath("/logbook?from=1&limit=100"));
        assertEquals(200, page.status());
        assertContains(page.body(), "\"count\":" + count);
        assertLifecycle(
                page.body(),
                1L,
                locationId,
                "OPEN");
        assertRegistration(
                page.body(),
                2L);
        assertLifecycle(
                page.body(),
                3L,
                locationId,
                "CLOSE");
    }

    private static void assertLogBookCount(
            HttpTestClient http,
            int count,
            long last)
            throws Exception {
        Response info = http.get(
                nodePath("/logbook"));
        assertEquals(200, info.status());
        assertContains(
                info.body(),
                "\"count\":" + count);
        assertContains(
                info.body(),
                "\"first\":1");
        assertContains(
                info.body(),
                "\"last\":" + last);
    }

    private static void assertLifecycle(
            String json,
            long sequence,
            int locationId,
            String code) {
        assertContains(
                json,
                "\"nodeId\":\"" + NODE_ID + "\"");
        assertContains(
                json,
                "\"seqNr\":" + sequence);
        assertContains(
                json,
                "\"locId\":" + locationId);
        assertContains(
                json,
                "\"recType\":\"NODE_INFO\"");
        assertContains(
                json,
                "\"code\":[\"" + code + "\"]");
    }

    private static void assertRegistration(
            String json,
            long sequence) {
        assertContains(
                json,
                "\"nodeId\":\"" + NODE_ID + "\"");
        assertContains(
                json,
                "\"seqNr\":" + sequence);
        assertContains(
                json,
                "\"locId\":24");
        assertContains(
                json,
                "\"recType\":\"AUTO_REG\"");
        assertContains(
                json,
                "\"regId\":\"" + REGISTRATION_ID + "\"");
        assertContains(
                json,
                "\"time\":\"" + EXPECTED_WIRE_TIME + "\"");
        assertContains(
                json,
                "\"code\":[\"ADD\"]");
    }

    private static String nodePath(
            String suffix) {
        return "/api/v1/node/"
                + NODE_ID
                + suffix;
    }

    private static void assertContains(
            String actual,
            String expected) {
        assertTrue(
                "Expected <" + expected + "> in <" + actual + ">",
                actual.contains(expected));
    }

    private static String combinedOutput(
            ProcessRun firstRun,
            ProcessRun secondRun) {
        StringBuilder output =
                new StringBuilder();
        if (firstRun != null) {
            output.append(
                    "=== run 1: lifecycle source order and idempotence ===")
                    .append(System.lineSeparator())
                    .append(firstRun.output());
        }
        if (secondRun != null) {
            output.append(
                    "=== run 2: lifecycle recovery and continuation ===")
                    .append(System.lineSeparator())
                    .append(secondRun.output());
        }
        return output.toString();
    }
}
