package io.github.brainboxemb.eventtiming.systemtest;

import io.github.brainboxemb.eventtiming.systemtest.TimingApplicationFixture.Ports;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient.Response;
import io.github.brainboxemb.eventtiming.systemtest.framework.ProcessRun;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Reusable black-box flow for VC-ST1-007 and VC-ST1-008.
 *
 * <p>Only the topology changes. Both cases exercise the same public IF-03
 * operations, per-node storage isolation and restart-recovery invariants.</p>
 */
final class MultiTopologyLogBookVerification {
    private static final String TIME = "2026-10-01T12:00:00Z";

    private MultiTopologyLogBookVerification() {
    }

    static void verify(String caseId, boolean twoSystems) throws Exception {
        TimingApplicationFixture fixture = TimingApplicationFixture.create(caseId, "A");
        BlackBoxEvidence evidence = fixture.evidence();
        ProcessRun first = null;
        ProcessRun restart = null;
        Throwable failure = null;
        boolean passed = false;

        try {
            Ports ports = fixture.reservePorts();
            File config = writeConfig(evidence, "run-1.yml", ports, twoSystems);
            first = fixture.start(config, caseId.toLowerCase(Locale.ROOT) + "-run-1");
            fixture.awaitReady(first, ports);

            HttpTestClient http = fixture.http(ports);
            assertInitialStatus(http);
            assertHistory(http, "A", 0, null, null, null);
            assertHistory(http, "B", 0, null, null, null);

            operateNode(http, "A", 24, "REG-A-001");
            assertHistory(http, "A", 3, "24", "REG-A-001", "A");
            assertHistory(http, "B", 0, null, null, null);

            operateNode(http, "B", 25, "REG-B-001");
            assertHistory(http, "B", 3, "25", "REG-B-001", "B");
            assertHistory(http, "A", 3, "24", "REG-A-001", "A");

            fixture.shutdown(first, ports);
            checkFiles(evidence, twoSystems);

            Ports restartPorts = fixture.reservePorts();
            File restartConfig = writeConfig(evidence, "run-2.yml", restartPorts, twoSystems);
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

    private static File writeConfig(
            BlackBoxEvidence evidence,
            String name,
            Ports ports,
            boolean twoSystems) throws Exception {
        StringBuilder config = new StringBuilder("timingSystems:\n");
        if (twoSystems) {
            appendSystem(config, "first", "SID-A", "A");
            appendSystem(config, "second", "SID-B", "B");
        } else {
            config.append("  one:\n")
                    .append("    timingSystemId: SID-9\n")
                    .append("    timingNodes:\n")
                    .append("      node-a:\n")
                    .append("        timingNodeId: A\n")
                    .append("      node-b:\n")
                    .append("        timingNodeId: B\n");
        }
        config.append("io:\n")
                .append("  storage:\n")
                .append("    timingData:\n")
                .append("      path: ")
                .append(twoSystems
                        ? "system-{SystemId}-node-{NodeId}-logbook.jsonl"
                        : "node-{NodeId}-logbook.jsonl")
                .append("\n")
                .append("presentation:\n")
                .append("  remoteShell:\n")
                .append("    bindAddress: 127.0.0.1\n")
                .append("    port: ").append(ports.shellPort()).append("\n")
                .append("  api:\n")
                .append("    http:\n")
                .append("      bindAddress: 127.0.0.1\n")
                .append("      port: ").append(ports.httpPort()).append("\n")
                .append("    webSocket:\n")
                .append("      bindAddress: 127.0.0.1\n")
                .append("      port: ").append(ports.webSocketPort()).append("\n")
                .append("logging:\n")
                .append("  level: INFO\n")
                .append("  file:\n")
                .append("    path: logs\n")
                .append("    rotateBytes: 1048576\n")
                .append("    retainedFiles: 5\n");
        File file = evidence.file(name);
        Files.write(file.toPath(), config.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static void appendSystem(
            StringBuilder config, String key, String systemId, String nodeId) {
        config.append("  ").append(key).append(":\n")
                .append("    timingSystemId: ").append(systemId).append("\n")
                .append("    timingNodes:\n")
                .append("      node-").append(nodeId).append(":\n")
                .append("        timingNodeId: ").append(nodeId).append("\n");
    }

    private static void assertInitialStatus(HttpTestClient http) throws Exception {
        Response status = http.get("/api/v1/status");
        assertEquals(200, status.status());
        assertContains(status.body(), "\"id\":\"A\"");
        assertContains(status.body(), "\"id\":\"B\"");
        // Node states must be fresh after restart rather than restored from LogBook.
        assertContains(status.body(), "\"state\":\"CLOSED\"");
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

    private static void checkFiles(BlackBoxEvidence evidence, boolean twoSystems)
            throws Exception {
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
