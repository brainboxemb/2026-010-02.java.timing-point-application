package io.github.brainboxemb.eventtiming.systemtest;

import io.github.brainboxemb.eventtiming.systemtest.TimingApplicationFixture.Ports;
import io.github.brainboxemb.eventtiming.systemtest.TestApplicationConfigFactory.Topology;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient.Response;
import io.github.brainboxemb.eventtiming.systemtest.framework.ProcessRun;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Reusable black-box flow for VC-ST1-007 and VC-ST1-008.
 *
 * <p>The YAML topology differs, but both test cases run the same public IF-03
 * procedure. Assert separate sequences, storage files and restart recovery;
 * the expected record contents are independent from the YAML parser.</p>
 */
final class MultiTopologyLogBookVerification {
    private static final String TIME = "2026-10-01T12:00:00Z";

    private MultiTopologyLogBookVerification() {
    }

    static void verify(String caseId, Topology topology) throws Exception {
        TimingApplicationFixture fixture = TimingApplicationFixture.create(caseId, "A");
        BlackBoxEvidence evidence = fixture.evidence();
        ProcessRun first = null;
        ProcessRun restart = null;
        Throwable failure = null;
        boolean passed = false;

        try {
            // Start the packaged SI-01 with the chosen YAML topology.
            Ports ports = fixture.reservePorts();
            File config = TestApplicationConfigFactory.write(
                    evidence, "run-1.yml", topology, ports, fixture.nodeId());
            first = fixture.start(config, caseId.toLowerCase(Locale.ROOT) + "-run-1");
            fixture.awaitReady(first, ports);

            HttpTestClient http = fixture.http(ports);
            assertInitialStatus(http);
            assertHistory(http, "A", 0, null, null, null);
            assertHistory(http, "B", 0, null, null, null);

            // Verify no cross-node mutation: B is still empty after A commits.
            operateNode(http, "A", 24, "REG-A-001");
            assertHistory(http, "A", 3, "24", "REG-A-001", "A");
            assertHistory(http, "B", 0, null, null, null);

            operateNode(http, "B", 25, "REG-B-001");
            assertHistory(http, "B", 3, "25", "REG-B-001", "B");
            assertHistory(http, "A", 3, "24", "REG-A-001", "A");

            // Compare physical files with independent expected node ownership.
            fixture.shutdown(first, ports);
            checkFiles(evidence, topology);

            // Reuse persisted files but launch a new SI-01 process and ports.
            Ports restartPorts = fixture.reservePorts();
            File restartConfig = TestApplicationConfigFactory.write(
                    evidence, "run-2.yml", topology, restartPorts, fixture.nodeId());
            restart = fixture.start(
                    restartConfig, caseId.toLowerCase(Locale.ROOT) + "-run-2");
            fixture.awaitReady(restart, restartPorts);
            HttpTestClient afterRestart = fixture.http(restartPorts);
            assertInitialStatus(afterRestart);
            assertHistory(afterRestart, "A", 3, "24", "REG-A-001", "A");
            assertHistory(afterRestart, "B", 3, "25", "REG-B-001", "B");
            fixture.shutdown(restart, restartPorts);

            evidence.verifyRuntimeLogging(
                    output(first) + System.lineSeparator() + output(restart));
            passed = true;
        } catch (Throwable ex) {
            failure = ex;
            throw new AssertionError(
                    caseId + " multi-topology black-box verification failed.\n"
                            + output(first) + "\n" + output(restart),
                    ex);
        } finally {
            fixture.cleanup(first);
            fixture.cleanup(restart);
            evidence.writeProcessOutput(output(first) + "\n" + output(restart));
            evidence.writeResult(passed, failure);
        }
    }

    private static void assertInitialStatus(HttpTestClient http) throws Exception {
        Response status = http.get("/api/v1/status");
        assertEquals(200, status.status());
        // Both nodes independently start CLOSED and without an active location,
        // including after restart; merely finding one CLOSED state is not enough.
        assertContains(
                status.body(), "\"id\":\"A\",\"locationId\":null,\"state\":\"CLOSED\"");
        assertContains(
                status.body(), "\"id\":\"B\",\"locationId\":null,\"state\":\"CLOSED\"");
    }

    private static void operateNode(
            HttpTestClient http, String node, int location, String registration)
            throws Exception {
        Response opened = http.post(
                "/api/v1/node/" + node + "/open",
                "{\"locationId\":" + location + "}");
        assertEquals(200, opened.status());
        assertContains(opened.body(), "\"result\":\"OPENED\"");

        Response added = http.post(
                "/api/v1/dev/node/" + node + "/auto-reg",
                "{\"id\":\"" + registration + "\",\"time\":\"" + TIME + "\"}");
        assertEquals(200, added.status());
        assertContains(added.body(), "\"seq\":2");

        Response closed = http.post("/api/v1/node/" + node + "/close", "");
        assertEquals(200, closed.status());
        assertContains(closed.body(), "\"result\":\"CLOSED\"");
    }

    private static void assertHistory(
            HttpTestClient http,
            String node,
            int count,
            String location,
            String registration,
            String recordNode) throws Exception {
        String url = "/api/v1/node/" + node + "/logbook";
        Response info = http.get(url);
        assertEquals(200, info.status());
        assertContains(info.body(), "\"count\":" + count);

        Response page = http.get(url + "?from=1&limit=100");
        assertEquals(200, page.status());
        assertContains(page.body(), "\"count\":" + count);
        if (count == 0) {
            assertContains(page.body(), "\"records\":[]");
            return;
        }
        assertContains(page.body(), "\"nodeId\":\"" + recordNode + "\"");
        assertContains(page.body(), "\"locId\":" + location);
        assertContains(page.body(), "\"regId\":\"" + registration + "\"");
        for (int n = 1; n <= 3; n++) {
            assertContains(page.body(), "\"seqNr\":" + n);
        }
        assertContains(page.body(), "\"code\":[\"OPEN\"]");
        assertContains(page.body(), "\"code\":[\"ADD\"]");
        assertContains(page.body(), "\"code\":[\"CLOSE\"]");
        assertTrue(!page.body().contains(
                "\"regId\":\"REG-" + ("A".equals(node) ? "B" : "A") + "-001\""));
    }

    private static void checkFiles(BlackBoxEvidence evidence, Topology topology)
            throws Exception {
        boolean twoSystems = topology == Topology.TWO_SYSTEMS_ONE_NODE_EACH;
        File a = evidence.file(twoSystems
                ? "system-SID-A-node-A-logbook.jsonl"
                : "node-A-logbook.jsonl");
        File b = evidence.file(twoSystems
                ? "system-SID-B-node-B-logbook.jsonl"
                : "node-B-logbook.jsonl");
        assertTrue("Expected separate A/B files", a.isFile() && b.isFile());
        assertTrue("A and B must use different files", !a.getCanonicalPath().equals(b.getCanonicalPath()));

        File[] allLogBooks = evidence.directory().listFiles(
                (dir, name) -> name.endsWith(".jsonl"));
        assertTrue(allLogBooks != null);
        assertEquals("Expected exactly two physical LogBook files", 2, allLogBooks.length);
        checkFile(a, "A", "24", "REG-A-001");
        checkFile(b, "B", "25", "REG-B-001");
    }

    private static void checkFile(
            File file, String node, String location, String registration)
            throws Exception {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals("Expected 3 committed records in " + file, 3, lines.size());
        String[] codes = {"OPEN", "ADD", "CLOSE"};
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            assertContains(line, "\"nodeId\":\"" + node + "\"");
            assertContains(line, "\"locId\":" + location);
            assertContains(line, "\"seqNr\":" + (i + 1));
            assertContains(line, "\"code\":[\"" + codes[i] + "\"]");
            assertTrue("Unexpected other TimingNode in file " + file,
                    !line.contains("\"nodeId\":\"" + ("A".equals(node) ? "B" : "A") + "\""));
        }
        assertContains(lines.get(1), "\"regId\":\"" + registration + "\"");
    }

    private static void assertContains(String json, String value) {
        assertTrue("Expected <" + value + "> in <" + json + ">", json.contains(value));
    }

    private static String output(ProcessRun run) {
        return run == null ? "" : run.output();
    }
}
