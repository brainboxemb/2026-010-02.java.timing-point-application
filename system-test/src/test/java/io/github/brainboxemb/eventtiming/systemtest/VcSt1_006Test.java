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
 * VC-ST1-006 — append-only registration revoke bookkeeping.
 */
public class VcSt1_006Test {
    private static final String NODE_ID = "A";
    private static final String REGISTRATION_ID = "N0006";
    private static final String REGISTRATION_TIME = "2026-10-01T12:06:00Z";
    private static final String EXPECTED_WIRE_TIME = "2026-10-01T12:06:00.00Z";

    @Test
    public void verifiesAppendOnlyRevokeAndRestartRecovery()
            throws Exception {
        TimingApplicationFixture fixture =
                TimingApplicationFixture.create("VC-ST1-006", NODE_ID);
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
                    "vc-st1-006-run-1-output");
            verifyRevokeHistory(
                    fixture,
                    firstRun,
                    firstPorts);

            Ports secondPorts = fixture.reservePorts();
            secondRun = fixture.start(
                    fixture.writeConfiguration(
                            "application-run-2.yml",
                            secondPorts),
                    "vc-st1-006-run-2-output");
            verifyRestartRecovery(
                    fixture,
                    secondRun,
                    secondPorts);

            evidence.verifyRuntimeLogging(
                    combinedOutput(firstRun, secondRun));
            passed = true;
        } catch (Throwable failure) {
            evidenceFailure = failure;
            throw new AssertionError(
                    "VC-ST1-006 black-box verification failed. Process output:\n"
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

    private static void verifyRevokeHistory(
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
            assertLifecycleOpen(
                    events.awaitEvent("TIMING_DATA_COMMITTED"),
                    1L);
            assertContains(
                    events.awaitEvent("STATUS_CHANGED"),
                    "\"state\":\"OPEN\"");

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
                    2L,
                    "ADD");

            Response firstRevoke = http.post(
                    nodePath("/registration/revoke"),
                    revokeBody());
            assertEquals(200, firstRevoke.status());
            assertContains(firstRevoke.body(), "\"seq\":3");
            assertRegistration(
                    events.awaitEvent("TIMING_DATA_COMMITTED"),
                    3L,
                    "REV");

            assertRecord(
                    http,
                    2L,
                    "ADD");
            assertRecord(
                    http,
                    3L,
                    "REV");

            Response repeatedRevoke = http.post(
                    nodePath("/registration/revoke"),
                    revokeBody());
            assertEquals(200, repeatedRevoke.status());
            assertContains(repeatedRevoke.body(), "\"seq\":4");
            assertRegistration(
                    events.awaitEvent("TIMING_DATA_COMMITTED"),
                    4L,
                    "REV");
            assertRecord(
                    http,
                    4L,
                    "REV");

            Response invalidManual = http.post(
                    nodePath("/registration/revoke"),
                    "{"
                            + "\"recordType\":\"MAN_REG\","
                            + "\"locationId\":24,"
                            + "\"regId\":\"" + REGISTRATION_ID + "\","
                            + "\"time\":\"" + REGISTRATION_TIME + "\""
                            + "}");
            assertEquals(400, invalidManual.status());
            assertContains(
                    invalidManual.body(),
                    "\"code\":\"INVALID_VALUE\"");

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

    private static void verifyRestartRecovery(
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

            assertLogBookCount(
                    http,
                    4,
                    4L);
            assertLifecycleOpen(
                    record(
                            http,
                            1L),
                    1L);
            assertRegistration(
                    record(
                            http,
                            2L),
                    2L,
                    "ADD");
            assertRegistration(
                    record(
                            http,
                            3L),
                    3L,
                    "REV");
            assertRegistration(
                    record(
                            http,
                            4L),
                    4L,
                    "REV");
        } finally {
            events.closeQuietly();
        }

        fixture.shutdown(
                run,
                ports);
    }

    private static void assertRecord(
            HttpTestClient http,
            long sequence,
            String action)
            throws Exception {
        assertRegistration(
                record(
                        http,
                        sequence),
                sequence,
                action);
    }

    private static String record(
            HttpTestClient http,
            long sequence)
            throws Exception {
        Response page = http.get(
                nodePath(
                        "/logbook?from="
                                + sequence
                                + "&limit=1"));
        assertEquals(200, page.status());
        assertContains(
                page.body(),
                "\"seqNr\":" + sequence);
        return page.body();
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

    private static void assertLifecycleOpen(
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
                "\"recType\":\"NODE_INFO\"");
        assertContains(
                json,
                "\"code\":[\"OPEN\"]");
    }

    private static void assertRegistration(
            String json,
            long sequence,
            String action) {
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
                "\"code\":[\"" + action + "\"]");
    }

    private static String revokeBody() {
        return "{"
                + "\"recordType\":\"AUTO_REG\","
                + "\"locationId\":24,"
                + "\"regId\":\"" + REGISTRATION_ID + "\","
                + "\"time\":\"" + REGISTRATION_TIME + "\""
                + "}";
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
                    "=== run 1: append-only revoke bookkeeping ===")
                    .append(System.lineSeparator())
                    .append(firstRun.output());
        }
        if (secondRun != null) {
            output.append(
                    "=== run 2: revoke history recovery ===")
                    .append(System.lineSeparator())
                    .append(secondRun.output());
        }
        return output.toString();
    }
}
