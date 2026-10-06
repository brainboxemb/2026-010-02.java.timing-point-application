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
 * <p>The formal verification flow remains here. Generic process, HTTP, WebSocket,
 * port-allocation and Remote Shell mechanics live in the system-test framework.</p>
 */
public class VcSt1_002Test {
    private static final String NODE_ID = "A";
    private static final String REGISTRATION_ID = "N0001";
    private static final String OBSERVATION_TIME = "2026-10-01T12:00:00Z";

    @Test
    public void controlsCommitsReconnectsRestartsAndRecoversLogBook() throws Exception {
        TimingApplicationFixture fixture =
                TimingApplicationFixture.create("VC-ST1-002", NODE_ID);
        BlackBoxEvidence evidence = fixture.evidence();

        ProcessRun firstRun = null;
        ProcessRun secondRun = null;
        boolean passed = false;
        Throwable evidenceFailure = null;

        try {
            // Run 1: lifecycle control, first registration and WebSocket reconnect.
            Ports firstPorts = fixture.reservePorts();
            firstRun = fixture.start(
                    fixture.writeConfiguration(
                            "application-run-1.yml",
                            firstPorts),
                    "vc-st1-002-run-1-output");
            verifyFirstRun(fixture, firstRun, firstPorts);

            File persistedTimingData = evidence.file("timing-data.jsonl");
            assertTrue(
                    "First run did not retain timing-data.jsonl",
                    persistedTimingData.isFile()
                            && persistedTimingData.length() > 0L);

            // Run 2: full process restart against the same persisted TimingData.
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

    /**
     * VTS run 1: establish operational state, commit sequence 1, verify history/live
     * observation, then reconnect without replaying history as a new live event.
     */
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

            // Initial authoritative state.
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
            assertContains(capabilities.body(), "\"supported\":true");
            assertContains(capabilities.body(), "\"enabled\":true");

            Response initialStatus = http.get("/api/v1/status");
            assertEquals(
                    "Unexpected initial status",
                    200,
                    initialStatus.status());
            assertNodeState(initialStatus.body(), null, "CLOSED");

            // OPEN carries and atomically applies its LocationId.
            Response open = http.post(
                    nodePath("/open"),
                    "{\"locationId\":24}");
            assertEquals("Unexpected open status", 200, open.status());
            assertContains(open.body(), "\"result\":\"OPENED\"");
            String openedEvent = events.awaitEvent("STATUS_CHANGED");
            assertContains(openedEvent, "\"locationId\":24");
            assertContains(openedEvent, "\"state\":\"OPEN\"");

            // Commit the deterministic first registration.
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
            assertContains(registration.body(), "\"seq\":1");

            String committedEvent =
                    events.awaitEvent("TIMING_DATA_COMMITTED");
            assertCommittedRegistration(committedEvent);

            assertSingleCommittedLogBookRecord(http);

            // Close while retaining the operational Location ID.
            Response close = http.post(nodePath("/close"), "");
            assertEquals("Unexpected close status", 200, close.status());
            assertContains(close.body(), "\"result\":\"CLOSED\"");
            String closedEvent = events.awaitEvent("STATUS_CHANGED");
            assertContains(closedEvent, "\"state\":\"CLOSED\"");
            assertContains(closedEvent, "\"locationId\":24");

            // Reconnect: current snapshot first; historical TimingData is not a new commit.
            events.closeQuietly();
            events = null;
            reconnect = fixture.connectEvents(ports);

            String reconnectSnapshot =
                    reconnect.awaitEvent("STATUS_SNAPSHOT");
            assertContains(reconnectSnapshot, "\"id\":\"" + NODE_ID + "\"");
            assertContains(reconnectSnapshot, "\"state\":\"CLOSED\"");
            assertContains(reconnectSnapshot, "\"locationId\":24");
            reconnect.assertNoEvent("TIMING_DATA_COMMITTED", 750L);

            assertSingleCommittedLogBookRecord(http);
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

    /**
     * VTS run 2 robustness evidence: restart from the same TimingData file and
     * recover history without restoring operational state or replaying history live.
     */
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

            Response recoveredStatus = http.get("/api/v1/status");
            assertEquals(
                    "Unexpected status after restart",
                    200,
                    recoveredStatus.status());
            assertNodeState(
                    recoveredStatus.body(),
                    null,
                    "CLOSED");

            assertSingleCommittedLogBookRecord(http);
        } finally {
            if (events != null) {
                events.closeQuietly();
            }
        }

        fixture.shutdown(run, ports);
    }

    private static void assertSingleCommittedLogBookRecord(
            HttpTestClient http)
            throws Exception {
        Response info = http.get(nodePath("/logbook"));
        assertEquals(
                "Unexpected LogBook metadata status",
                200,
                info.status());
        assertContains(info.body(), "\"count\":1");
        assertContains(info.body(), "\"first\":1");
        assertContains(info.body(), "\"last\":1");

        Response page = http.get(
                nodePath("/logbook?from=1&limit=100"));
        assertEquals(
                "Unexpected LogBook range status",
                200,
                page.status());
        assertContains(page.body(), "\"count\":1");
        assertContains(page.body(), "\"next\":null");
        assertCommittedRegistration(page.body());
    }

    private static void assertNodeState(
            String json,
            Integer locationId,
            String state) {
        assertContains(json, "\"nodes\":[{");
        assertContains(json, "\"id\":\"" + NODE_ID + "\"");
        if (locationId == null) {
            assertContains(json, "\"locationId\":null");
        } else {
            assertContains(
                    json,
                    "\"locationId\":" + locationId);
        }
        assertContains(json, "\"state\":\"" + state + "\"");
    }

    private static void assertCommittedRegistration(String json) {
        assertContains(json, "\"v\":1");
        assertContains(json, "\"nodeId\":\"" + NODE_ID + "\"");
        assertContains(json, "\"seqNr\":1");
        assertContains(json, "\"locId\":24");
        assertContains(json, "\"recType\":\"AUTO_REG\"");
        assertContains(
                json,
                "\"time\":\"" + OBSERVATION_TIME + "\"");
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
                    "=== run 1: commit and WebSocket reconnect ===")
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
