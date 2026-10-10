package io.github.brainboxemb.eventtiming.systemtest;

import java.io.File;

import io.github.brainboxemb.eventtiming.systemtest.TimingApplicationFixture.Ports;
import io.github.brainboxemb.eventtiming.systemtest.framework.EventStream;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient.Response;
import io.github.brainboxemb.eventtiming.systemtest.framework.ProcessRun;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * VC-ST1-002 — control and observe first committed registration.
 *
 * <p>The current stream includes lifecycle TimingData. This case therefore
 * verifies OPEN, registration ADD and CLOSE in one source sequence before
 * restart recovery.</p>
 */
public class VcSt1_002Test {
    private static final String NODE_ID = "A";
    private static final String REGISTRATION_ID =
            TestParticipantIds.normalRegistration(1);
    private static final String OBSERVATION_TIME = "2026-10-01T12:00:00Z";
    private static final String EXPECTED_WIRE_TIME = "2026-10-01T12:00:00.00Z";

    @Test
    public void controlsCommitsReconnectsRestartsAndRecoversLogBook()
            throws Exception {
        TimingApplicationFixture fixture =
                TimingApplicationFixture.create("VC-ST1-002", NODE_ID);
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
                    "vc-st1-002-run-1-output");
            verifyFirstRun(fixture, firstRun, firstPorts);

            File persistedTimingData = fixture.logBookFile();
            assertTrue(
                    "First run did not retain " + persistedTimingData.getName(),
                    persistedTimingData.isFile()
                            && persistedTimingData.length() > 0L);

            Ports secondPorts = fixture.reservePorts();
            secondRun = fixture.start(
                    fixture.writeConfiguration(
                            "application-run-2.yml",
                            secondPorts),
                    "vc-st1-002-run-2-output");
            verifyRestartRecovery(fixture, secondRun, secondPorts);

            evidence.verifyRuntimeLogging(
                    combinedOutput(firstRun, secondRun));
            passed = true;
        } catch (Throwable failure) {
            evidenceFailure = failure;
            throw new AssertionError(
                    "VC-ST1-002 black-box verification failed. Process output:\n"
                            + combinedOutput(firstRun, secondRun),
                    failure);
        } finally {
            fixture.cleanup(firstRun);
            fixture.cleanup(secondRun);
            evidence.writeProcessOutput(
                    combinedOutput(firstRun, secondRun));
            evidence.writeResult(passed, evidenceFailure);
        }
    }

    private static void verifyFirstRun(
            TimingApplicationFixture fixture,
            ProcessRun run,
            Ports ports)
            throws Exception {
        fixture.awaitReady(run, ports);
        HttpTestClient http = fixture.http(ports);
        EventStream events = null;
        EventStream reconnect = null;

        try {
            events = fixture.connectEvents(ports);

            String initialSnapshot = events.awaitEvent("STATUS_SNAPSHOT");
            assertContains(initialSnapshot, "\"id\":\"" + NODE_ID + "\"");
            assertContains(initialSnapshot, "\"locationId\":null");
            assertContains(initialSnapshot, "\"state\":\"CLOSED\"");

            Response capabilities = http.get("/api/v1/capabilities");
            assertEquals(
                    "Unexpected capabilities status",
                    200,
                    capabilities.status());
            assertContains(
                    capabilities.body(),
                    "\"id\":\"DIRECT_REGISTRATION_SIMULATION\"");
            assertContains(capabilities.body(), "\"enabled\":true");

            Response open = http.post(
                    nodePath("/open"),
                    "{\"locationId\":24}");
            assertEquals("Unexpected open status", 200, open.status());
            assertContains(open.body(), "\"result\":\"OPENED\"");

            String openCommitted =
                    events.awaitEvent("TIMING_DATA_COMMITTED");
            assertLifecycle(openCommitted, 1L, "OPEN", 24);

            String openedEvent = events.awaitEvent("STATUS_CHANGED");
            assertContains(openedEvent, "\"locationId\":24");
            assertContains(openedEvent, "\"state\":\"OPEN\"");

            Response registration = http.post(
                    "/api/v1/dev/node/" + NODE_ID + "/auto-reg",
                    "{"
                            + "\"id\":\"" + REGISTRATION_ID + "\","
                            + "\"time\":\"" + OBSERVATION_TIME + "\""
                            + "}");
            assertEquals(
                    "Unexpected auto-reg status",
                    200,
                    registration.status());
            assertContains(registration.body(), "\"seq\":2");

            String registrationCommitted =
                    events.awaitEvent("TIMING_DATA_COMMITTED");
            assertCommittedRegistration(
                    registrationCommitted,
                    2L);

            assertLogBook(
                    http,
                    2,
                    2L,
                    false);

            Response close = http.post(nodePath("/close"), "");
            assertEquals("Unexpected close status", 200, close.status());
            assertContains(close.body(), "\"result\":\"CLOSED\"");

            String closeCommitted =
                    events.awaitEvent("TIMING_DATA_COMMITTED");
            assertLifecycle(closeCommitted, 3L, "CLOSE", 24);

            String closedEvent = events.awaitEvent("STATUS_CHANGED");
            assertContains(closedEvent, "\"state\":\"CLOSED\"");
            assertContains(closedEvent, "\"locationId\":24");

            events.closeQuietly();
            events = null;
            reconnect = fixture.connectEvents(ports);

            String reconnectSnapshot =
                    reconnect.awaitEvent("STATUS_SNAPSHOT");
            assertContains(reconnectSnapshot, "\"id\":\"" + NODE_ID + "\"");
            assertContains(reconnectSnapshot, "\"state\":\"CLOSED\"");
            assertContains(reconnectSnapshot, "\"locationId\":24");
            reconnect.assertNoEvent("TIMING_DATA_COMMITTED", 750L);

            assertLogBook(
                    http,
                    3,
                    3L,
                    true);
        } finally {
            if (events != null) {
                events.closeQuietly();
            }
            if (reconnect != null) {
                reconnect.closeQuietly();
            }
        }

        fixture.shutdown(run, ports);
    }

    private static void verifyRestartRecovery(
            TimingApplicationFixture fixture,
            ProcessRun run,
            Ports ports)
            throws Exception {
        fixture.awaitReady(run, ports);
        HttpTestClient http = fixture.http(ports);
        EventStream events = null;

        try {
            events = fixture.connectEvents(ports);

            String recoveredSnapshot =
                    events.awaitEvent("STATUS_SNAPSHOT");
            assertContains(recoveredSnapshot, "\"id\":\"" + NODE_ID + "\"");
            assertContains(recoveredSnapshot, "\"state\":\"CLOSED\"");
            assertContains(recoveredSnapshot, "\"locationId\":null");
            events.assertNoEvent("TIMING_DATA_COMMITTED", 750L);

            assertLogBook(
                    http,
                    3,
                    3L,
                    true);
        } finally {
            if (events != null) {
                events.closeQuietly();
            }
        }

        fixture.shutdown(run, ports);
    }

    private static void assertLogBook(
            HttpTestClient http,
            int count,
            long last,
            boolean requireClose)
            throws Exception {
        Response info = http.get(nodePath("/logbook"));
        assertEquals(
                "Unexpected LogBook metadata status",
                200,
                info.status());
        assertContains(info.body(), "\"count\":" + count);
        assertContains(info.body(), "\"first\":1");
        assertContains(info.body(), "\"last\":" + last);

        Response page = http.get(
                nodePath("/logbook?from=1&limit=100"));
        assertEquals(
                "Unexpected LogBook range status",
                200,
                page.status());
        assertContains(page.body(), "\"count\":" + count);
        assertContains(page.body(), "\"next\":null");
        assertLifecycle(page.body(), 1L, "OPEN", 24);
        assertCommittedRegistration(page.body(), 2L);
        if (requireClose) {
            assertLifecycle(page.body(), 3L, "CLOSE", 24);
        }
    }

    private static void assertLifecycle(
            String json,
            long sequence,
            String code,
            int locationId) {
        assertContains(json, "\"v\":1");
        assertContains(json, "\"nodeId\":\"" + NODE_ID + "\"");
        assertContains(json, "\"seqNr\":" + sequence);
        assertContains(json, "\"locId\":" + locationId);
        assertContains(json, "\"recType\":\"NODE_INFO\"");
        assertContains(json, "\"code\":[\"" + code + "\"]");
    }

    private static void assertCommittedRegistration(
            String json,
            long sequence) {
        assertContains(json, "\"v\":1");
        assertContains(json, "\"nodeId\":\"" + NODE_ID + "\"");
        assertContains(json, "\"seqNr\":" + sequence);
        assertContains(json, "\"locId\":24");
        assertContains(json, "\"recType\":\"AUTO_REG\"");
        assertContains(
                json,
                "\"time\":\"" + EXPECTED_WIRE_TIME + "\"");
        assertContains(
                json,
                "\"regId\":\"" + REGISTRATION_ID + "\"");
        assertContains(json, "\"code\":[\"ADD\"]");
        assertContains(json, "\"recTime\":");
    }

    private static String nodePath(String suffix) {
        return "/api/v1/node/" + NODE_ID + suffix;
    }

    private static void assertContains(String actual, String expected) {
        assertTrue(
                "Expected <" + expected + "> in <" + actual + ">",
                actual.contains(expected));
    }

    private static String combinedOutput(
            ProcessRun firstRun,
            ProcessRun secondRun) {
        StringBuilder output = new StringBuilder();
        if (firstRun != null) {
            output.append(
                    "=== run 1: lifecycle, registration and reconnect ===")
                    .append(System.lineSeparator())
                    .append(firstRun.output());
        }
        if (secondRun != null) {
            output.append(
                    "=== run 2: process restart and LogBook recovery ===")
                    .append(System.lineSeparator())
                    .append(secondRun.output());
        }
        return output.toString();
    }
}
