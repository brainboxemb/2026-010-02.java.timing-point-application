package io.github.brainboxemb.eventtiming.systemtest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.brainboxemb.eventtiming.systemtest.TimingApplicationFixture.Ports;
import io.github.brainboxemb.eventtiming.systemtest.framework.EventStream;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient.Response;
import io.github.brainboxemb.eventtiming.systemtest.framework.ProcessRun;
import io.github.brainboxemb.eventtiming.systemtest.framework.RemoteShellClient;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * VC-ST1-004 — keep diagnostics available after TimingData recovery failure.
 *
 * <p>This case deliberately supplies valid persisted TimingData owned by another
 * TimingNodeId. SI-01 must reject that recovery input without terminating the
 * application-level diagnostic interfaces.</p>
 */
public class VcSt1_004Test {
    private static final String NODE_ID = "A";
    private static final String OTHER_NODE_ID = "B";

    @Test
    public void containsRecoveryFailureAndKeepsDiagnosticsAvailable()
            throws Exception {
        TimingApplicationFixture fixture =
                TimingApplicationFixture.create("VC-ST1-004", NODE_ID);
        BlackBoxEvidence evidence = fixture.evidence();
        Ports ports = fixture.reservePorts();
        ProcessRun run = null;
        EventStream events = null;
        boolean passed = false;
        Throwable evidenceFailure = null;

        try {
            writeMismatchedTimingData(evidence);

            run = fixture.start(
                    fixture.writeConfiguration("application.yml", ports),
                    "vc-st1-004-process-output");
            fixture.awaitReady(run, ports);
            assertTrue(
                    "SI-01 exited instead of containing the TimingNode recovery failure",
                    run.isAlive());

            HttpTestClient http = fixture.http(ports);

            Response status = http.get("/api/v1/status");
            assertEquals("Unexpected degraded /status HTTP status", 200, status.status());
            assertErrorStatus(status.body());

            String shellStatus =
                    RemoteShellClient.requestStatus(ports.shellPort());
            assertContains(shellStatus, "Id        : " + NODE_ID);
            assertContains(shellStatus, "State     : ERROR");
            assertContains(
                    shellStatus,
                    "Problem   : ERROR TIMING_DATA_RECOVERY_FAILED");
            assertContains(shellStatus, "TimingData recovery failed for " + NODE_ID);
            assertContains(shellStatus, "belongs to " + OTHER_NODE_ID);

            Response open = http.post(
                    "/api/v1/node/" + NODE_ID + "/open",
                    "{\"locationId\":24}");
            assertEquals(
                    "ERROR TimingNode must reject OPEN explicitly",
                    503,
                    open.status());
            assertContains(open.body(), "\"code\":\"OPERATION_FAILED\"");
            assertContains(open.body(), "TimingData recovery failed for " + NODE_ID);

            Response afterRejectedOpen = http.get("/api/v1/status");
            assertEquals(200, afterRejectedOpen.status());
            assertErrorStatus(afterRejectedOpen.body());

            events = fixture.connectEvents(ports);
            String firstSnapshot = events.awaitEvent("STATUS_SNAPSHOT");
            assertErrorSnapshot(firstSnapshot);
            events.closeQuietly();
            events = null;

            events = fixture.connectEvents(ports);
            String reconnectSnapshot = events.awaitEvent("STATUS_SNAPSHOT");
            assertErrorSnapshot(reconnectSnapshot);

            assertContains(run.output(), "TimingData recovery failed for " + NODE_ID);

            fixture.shutdown(run, ports);
            evidence.verifyRuntimeLogging(run.output());
            passed = true;
        } catch (Throwable failure) {
            evidenceFailure = failure;
            throw new AssertionError(
                    "VC-ST1-004 black-box verification failed. Process output:\n"
                            + outputOf(run),
                    failure);
        } finally {
            if (events != null) {
                events.closeQuietly();
            }
            fixture.cleanup(run);
            evidence.writeProcessOutput(outputOf(run));
            evidence.writeResult(passed, evidenceFailure);
        }
    }

    private static void writeMismatchedTimingData(
            BlackBoxEvidence evidence)
            throws Exception {
        String record = "{"
                + "\"v\":1,"
                + "\"nodeId\":\"" + OTHER_NODE_ID + "\","
                + "\"seqNr\":1,"
                + "\"locId\":24,"
                + "\"recType\":\"AUTO_REG\","
                + "\"time\":\"2026-10-01T12:00:00Z\","
                + "\"regId\":\"N0001\","
                + "\"code\":[\"ADD\"],"
                + "\"recTime\":\"2026-10-01T12:00:01Z\""
                + "}\n";
        Files.write(
                evidence.file("timing-data.jsonl").toPath(),
                record.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertErrorStatus(String json) {
        assertContains(json, "\"id\":\"" + NODE_ID + "\"");
        assertContains(json, "\"locationId\":null");
        assertContains(json, "\"state\":\"ERROR\"");
        assertContains(
                json,
                "\"code\":\"TIMING_DATA_RECOVERY_FAILED\"");
        assertContains(json, "\"severity\":\"ERROR\"");
        assertContains(json, "\"nodeId\":\"" + NODE_ID + "\"");
        assertContains(json, "TimingData recovery failed for " + NODE_ID);
        assertContains(json, "belongs to " + OTHER_NODE_ID);
    }

    private static void assertErrorSnapshot(String json) {
        assertContains(json, "\"eventType\":\"STATUS_SNAPSHOT\"");
        assertErrorStatus(json);
    }

    private static void assertContains(String actual, String expected) {
        assertTrue(
                "Expected <" + expected + "> in <" + actual + ">",
                actual.contains(expected));
    }

    private static String outputOf(ProcessRun run) {
        return run == null ? "" : run.output();
    }
}
