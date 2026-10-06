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
 * VC-ST1-001 — query and resynchronise first-executable status.
 *
 * <p>The infrastructure for process launch, HTTP/WebSocket transport, port allocation
 * and controlled shutdown lives in the reusable system-test framework. This class is
 * intentionally kept close to the formal VTS procedure.</p>
 */
public class VcSt1_001Test {
    private static final String NODE_ID = "A";

    @Test
    public void verifiesVersionStatusReconnectAndControlledShutdown() throws Exception {
        TimingApplicationFixture fixture =
                TimingApplicationFixture.create("VC-ST1-001", NODE_ID);
        BlackBoxEvidence evidence = fixture.evidence();
        Ports ports = fixture.reservePorts();
        ProcessRun run = null;
        EventStream events = null;
        boolean passed = false;
        Throwable evidenceFailure = null;

        try {
            // 1-2. Start SI-01 and wait until IF-03 is ready.
            run = fixture.start(
                    fixture.writeConfiguration("application.yml", ports),
                    "vc-st1-001-process-output");
            fixture.awaitReady(run, ports);
            HttpTestClient http = fixture.http(ports);

            // 3. Query build/version identity.
            Response version = http.get("/api/v1/version");
            assertEquals("Unexpected /version HTTP status", 200, version.status());
            assertContains(version.body(), "\"application\":\"timing-application\"");
            assertContains(
                    version.body(),
                    "\"version\":\""
                            + TimingApplicationFixture.requiredProperty(
                                    "eventTiming.expectedProjectVersion")
                            + "\"");
            assertContains(version.body(), "\"apiVersion\":\"1\"");
            assertTrue(
                    "Expected exact Git revision in /version: " + version.body(),
                    version.body().matches(
                            ".*\\\"revision\\\":\\\"[0-9a-f]{40}\\\".*"));

            // 4. Query current status.
            Response status = http.get("/api/v1/status");
            assertEquals("Unexpected /status HTTP status", 200, status.status());
            assertStatusSemantics(status.body());

            // 5. First WebSocket session must begin with a complete snapshot.
            events = fixture.connectEvents(ports);
            String firstSnapshot = events.awaitEvent("STATUS_SNAPSHOT");
            assertSnapshot(firstSnapshot);
            events.closeQuietly();
            events = null;

            // 6-7. Reconnect and require a fresh complete snapshot.
            events = fixture.connectEvents(ports);
            String reconnectSnapshot = events.awaitEvent("STATUS_SNAPSHOT");
            assertSnapshot(reconnectSnapshot);

            // 8. HTTP status and reconnect snapshot describe the same state.
            Response resynchronisedStatus = http.get("/api/v1/status");
            assertEquals(
                    "Unexpected resynchronisation /status HTTP status",
                    200,
                    resynchronisedStatus.status());
            assertStatusSemantics(resynchronisedStatus.body());
            assertSnapshotSemanticsMatchStatus(
                    reconnectSnapshot,
                    resynchronisedStatus.body());

            // 9. Shut down through the supported external control path.
            fixture.shutdown(run, ports);
            evidence.verifyRuntimeLogging(run.output());
            passed = true;
        } catch (Throwable failure) {
            evidenceFailure = failure;
            throw new AssertionError(
                    "VC-ST1-001 black-box verification failed. Process output:\n"
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

    private static void assertSnapshot(String json) {
        assertContains(json, "\"eventType\":\"STATUS_SNAPSHOT\"");
        assertContains(json, "\"nodes\":[{");
        assertContains(json, "\"id\":\"" + NODE_ID + "\"");
        assertContains(json, "\"locationId\":null");
        assertContains(json, "\"state\":\"CLOSED\"");
    }

    private static void assertStatusSemantics(String json) {
        assertContains(json, "\"nodes\":[{");
        assertContains(json, "\"id\":\"" + NODE_ID + "\"");
        assertContains(json, "\"locationId\":null");
        assertContains(json, "\"state\":\"CLOSED\"");
        assertContains(json, "\"problems\":[]");
    }

    private static void assertSnapshotSemanticsMatchStatus(
            String snapshot,
            String status) {
        for (String field : new String[] {
                "\"id\":\"" + NODE_ID + "\"",
                "\"locationId\":null",
                "\"state\":\"CLOSED\""
        }) {
            assertContains(snapshot, field);
            assertContains(status, field);
        }
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
