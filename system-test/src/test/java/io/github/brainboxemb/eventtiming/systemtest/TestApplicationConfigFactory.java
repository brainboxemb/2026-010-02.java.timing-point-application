package io.github.brainboxemb.eventtiming.systemtest;

import io.github.brainboxemb.eventtiming.systemtest.TimingApplicationFixture.Ports;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads readable IF-11 YAML fixtures and supplies per-run port numbers.
 *
 * <p>The complete test configurations live under src/test/resources/configuration.
 * Only @PORT@ and @NODE_ID@ markers are substituted here. Application-level
 * {NodeId} and {SystemId} path templates remain untouched so the black-box
 * tests verify the actual application's resolution of those templates.</p>
 */
final class TestApplicationConfigFactory {
    enum Topology {
        SINGLE_NODE("single-node.yml"),
        ONE_SYSTEM_TWO_NODES("one-system-two-nodes.yml"),
        TWO_SYSTEMS_ONE_NODE_EACH("two-systems-one-node-each.yml");

        private final String resource;

        Topology(String resource) {
            this.resource = resource;
        }
    }

    private static final Pattern UNRESOLVED = Pattern.compile("@[A-Z_]+@");

    private TestApplicationConfigFactory() {
    }

    static File write(
            BlackBoxEvidence evidence,
            String fileName,
            Topology topology,
            Ports ports,
            String nodeId) throws IOException {
        if (evidence == null || topology == null || ports == null) {
            throw new IllegalArgumentException("evidence, topology and ports are required");
        }
        if (nodeId == null || nodeId.trim().isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        String result = readResource(topology.resource)
                .replace("@NODE_ID@", nodeId)
                .replace("@SHELL_PORT@", Integer.toString(ports.shellPort()))
                .replace("@HTTP_PORT@", Integer.toString(ports.httpPort()))
                .replace("@WEB_SOCKET_PORT@", Integer.toString(ports.webSocketPort()));
        Matcher unknown = UNRESOLVED.matcher(result);
        if (unknown.find()) {
            throw new IllegalArgumentException(
                    "Unresolved test variable " + unknown.group()
                            + " in " + topology.resource);
        }

        // Retain the resolved, directly runnable YAML alongside test evidence.
        File output = evidence.file(fileName);
        Files.write(output.toPath(), result.getBytes(StandardCharsets.UTF_8));
        return output;
    }

    private static String readResource(String resource) throws IOException {
        String path = "/configuration/" + resource;
        try (InputStream input = TestApplicationConfigFactory.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing test configuration resource: " + path);
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int bytes;
            while ((bytes = input.read(buffer)) != -1) {
                content.write(buffer, 0, bytes);
            }
            return new String(content.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
