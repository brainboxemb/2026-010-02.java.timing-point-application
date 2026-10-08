package io.github.brainboxemb.eventtiming.systemtest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import io.github.brainboxemb.eventtiming.systemtest.framework.EventStream;
import io.github.brainboxemb.eventtiming.systemtest.framework.HttpTestClient;
import io.github.brainboxemb.eventtiming.systemtest.framework.ProcessRun;
import io.github.brainboxemb.eventtiming.systemtest.framework.RemoteShellClient;
import io.github.brainboxemb.eventtiming.systemtest.framework.TestPorts;

/**
 * SI-01-specific fixture layered on top of the generic black-box test framework.
 *
 * <p>This class owns only application launch configuration and supported public
 * readiness/shutdown behaviour. Verification semantics remain in the VC test
 * classes.</p>
 */
final class TimingApplicationFixture {
    private static final long START_TIMEOUT_MILLIS = 15000L;
    private static final long EXIT_TIMEOUT_MILLIS = 10000L;

    private final File appJar;
    private final String nodeId;
    private final BlackBoxEvidence evidence;

    private TimingApplicationFixture(
            File appJar,
            String nodeId,
            BlackBoxEvidence evidence) {
        this.appJar = appJar;
        this.nodeId = nodeId;
        this.evidence = evidence;
    }

    static TimingApplicationFixture create(
            String verificationId,
            String nodeId)
            throws IOException {
        File appJar = new File(requiredProperty("eventTiming.appJar"))
                .getAbsoluteFile();
        if (!appJar.isFile()) {
            throw new AssertionError(
                    "Packaged application JAR does not exist: " + appJar);
        }
        BlackBoxEvidence evidence = BlackBoxEvidence.create(
                requiredProperty("eventTiming.evidenceDir"),
                verificationId);
        return new TimingApplicationFixture(appJar, nodeId, evidence);
    }

    Ports reservePorts() throws IOException {
        int[] values = TestPorts.reserve(3);
        return new Ports(values[0], values[1], values[2]);
    }

    File writeConfiguration(String name, Ports ports) throws IOException {
        File config = evidence.file(name);
        Files.write(
                config.toPath(),
                configuration(ports).getBytes(StandardCharsets.UTF_8));
        return config;
    }

    ProcessRun start(File config, String collectorName) throws IOException {
        return ProcessRun.startJar(
                appJar,
                config,
                evidence.directory(),
                collectorName);
    }

    void awaitReady(ProcessRun run, Ports ports) throws Exception {
        HttpTestClient http = http(ports);
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MILLIS;
        IOException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            if (!run.isAlive()) {
                throw new AssertionError(
                        "Application exited before IF-03 became ready. Output:\n"
                                + run.output());
            }
            try {
                if (http.get("/api/v1/status").status() == 200) {
                    return;
                }
            } catch (IOException ex) {
                lastFailure = ex;
            }
            Thread.sleep(100L);
        }
        throw new IOException(
                "Timed out waiting for IF-03 HTTP endpoint",
                lastFailure);
    }

    HttpTestClient http(Ports ports) {
        return new HttpTestClient(ports.httpPort());
    }

    EventStream connectEvents(Ports ports) throws Exception {
        return EventStream.connect(ports.webSocketPort());
    }

    void shutdown(ProcessRun run, Ports ports) throws Exception {
        RemoteShellClient.requestQuit(ports.shellPort());
        run.awaitSuccessfulExit(EXIT_TIMEOUT_MILLIS);
    }

    void cleanup(ProcessRun run) throws InterruptedException {
        if (run != null) {
            run.cleanup();
        }
    }

    BlackBoxEvidence evidence() {
        return evidence;
    }

    String nodeId() {
        return nodeId;
    }

    static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required system property: " + name);
        }
        return value;
    }

    private String configuration(Ports ports) {
        return "timingSystems:\n"
                + "  timing-system-01:\n"
                + "    timingSystemId: timing-system-01\n"
                + "    timingNodes:\n"
                + "      timing-node-01:\n"
                + "        timingNodeId: " + nodeId + "\n"
                + "io:\n"
                + "  storage:\n"
                + "    timingData:\n"
                + "      path: timing-data.jsonl\n"
                + "presentation:\n"
                + "  remoteShell:\n"
                + "    bindAddress: 127.0.0.1\n"
                + "    port: " + ports.shellPort() + "\n"
                + "  api:\n"
                + "    http:\n"
                + "      bindAddress: 127.0.0.1\n"
                + "      port: " + ports.httpPort() + "\n"
                + "    webSocket:\n"
                + "      bindAddress: 127.0.0.1\n"
                + "      port: " + ports.webSocketPort() + "\n"
                + "logging:\n"
                + "  level: INFO\n"
                + "  file:\n"
                + "    path: logs\n"
                + "    rotateBytes: 1048576\n"
                + "    retainedFiles: 5\n";
    }

    /** Three externally visible endpoints used by one SI-01 process run. */
    static final class Ports {
        private final int shellPort;
        private final int httpPort;
        private final int webSocketPort;

        private Ports(
                int shellPort,
                int httpPort,
                int webSocketPort) {
            this.shellPort = shellPort;
            this.httpPort = httpPort;
            this.webSocketPort = webSocketPort;
        }

        int shellPort() {
            return shellPort;
        }

        int httpPort() {
            return httpPort;
        }

        int webSocketPort() {
            return webSocketPort;
        }
    }
}
