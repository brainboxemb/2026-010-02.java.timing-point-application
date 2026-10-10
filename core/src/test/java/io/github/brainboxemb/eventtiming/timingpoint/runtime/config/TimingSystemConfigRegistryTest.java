package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Arrays;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class TimingSystemConfigRegistryTest {
    @Test
    public void singleNodeConfigUsesMatchingSystemId() {
        Config config =
                new Config(
                        new NodeId("A"),
                        new Presentation(
                                null,
                                null));

        assertEquals(
                "A",
                config.timingSystems()
                        .get(0)
                        .timingSystemId());
    }

    @Test
    public void multiNodeDefaultIsNine() {
        Config config = new Config(
                Arrays.asList(node("A", path("a.jsonl")),
                        node("B", path("b.jsonl"))),
                new Presentation(null, null),
                null, null,
                Config.REFERENCE_PROVIDER_ID,
                Config.REFERENCE_PROVIDER_ID);
        assertEquals("9", config.timingSystems().get(0).timingSystemId());
    }

    @Test
    public void rejectsPrefixedSystemIdentifier() {
        expectInvalid(
                "TimingSystemId must be one character A-Z or 1-9",
                () -> system("SID-A", node("A", path("a.jsonl"))));
    }

    @Test
    public void rejectsSingleNodeSystemIdDifferentFromNode() {
        expectInvalid(
                "Single-node TimingSystemId must equal TimingNodeId A",
                () -> system("B", node("A", path("a.jsonl"))));
    }

    @Test
    public void rejectsMultiNodeSystemIdMatchingOwnedNode() {
        expectInvalid(
                "Multi-node TimingSystemId A must differ from its TimingNodeIds",
                () -> system("A", node("A", path("a.jsonl")),
                        node("B", path("b.jsonl"))));
    }

    @Test
    public void rejectsMultiNodeSystemIdMatchingAnotherSystemsNode() {
        expectInvalid(
                "Multi-node TimingSystemId 9 must differ from all TimingNodeIds",
                () -> new TimingSystemConfigRegistry(Arrays.asList(
                        system("9", node("A", path("a.jsonl")),
                                node("B", path("b.jsonl"))),
                        system("8", node("9", path("9.jsonl")),
                                node("C", path("c.jsonl"))))));
    }

    private static void expectInvalid(String message, Runnable operation) {
        try {
            operation.run();
            fail("Expected invalid TimingSystemId: " + message);
        } catch (IllegalArgumentException expected) {
            assertEquals(message, expected.getMessage());
        }
    }

    @Test
    public void preservesSystemOrderAndProvidesTypedLookup() {
        Config.TimingSystemConfig first =
                system(
                        "A",
                        node(
                                "A",
                                path("a.jsonl")));
        Config.TimingSystemConfig second =
                system(
                        "B",
                        node(
                                "B",
                                path("b.jsonl")));

        TimingSystemConfigRegistry registry =
                new TimingSystemConfigRegistry(
                        java.util.Arrays.asList(
                                first,
                                second));

        assertEquals(
                2,
                registry.systems().size());
        assertSame(
                first,
                registry.systems().get(0));
        assertSame(
                second,
                registry.system("B"));
        assertEquals(
                new NodeId("B"),
                registry.timingNode(
                                new NodeId("B"))
                        .timingNodeId());
    }

    @Test
    public void rejectsDuplicateApplicationWideTimingNodeId() {
        Config.TimingNodeConfig first =
                node(
                        "A",
                        path("first.jsonl"));
        Config.TimingNodeConfig second =
                node(
                        "A",
                        path("second.jsonl"));

        try {
            new TimingSystemConfigRegistry(
                    java.util.Arrays.asList(
                            system(
                                    "9",
                                    first,
                                    node("B", path("node-b.jsonl"))),
                            system(
                                    "8",
                                    second,
                                    node("C", path("node-c.jsonl")))));
            fail("Expected duplicate TimingNode id to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                    "Duplicate application-wide TimingNode id A",
                    expected.getMessage());
        }
    }

    @Test
    public void rejectsDuplicateNormalizedTimingDataStoragePath() {
        Path first =
                Paths.get(
                        "target",
                        "registry-test",
                        "node.jsonl");
        Path second =
                Paths.get(
                        "target",
                        "registry-test",
                        ".",
                        "node.jsonl");

        try {
            new TimingSystemConfigRegistry(
                    java.util.Arrays.asList(
                            system(
                                    "A",
                                    node(
                                            "A",
                                            first)),
                            system(
                                    "B",
                                    node(
                                            "B",
                                            second))));
            fail("Expected duplicate TimingData path to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                    "Duplicate TimingData storage path "
                            + second,
                    expected.getMessage());
        }
    }

    private static Config.TimingSystemConfig system(
            String id,
            Config.TimingNodeConfig... node) {
        return new Config.TimingSystemConfig(
                id,
                java.util.Arrays.asList(node),
                Config.REFERENCE_PROVIDER_ID,
                Config.REFERENCE_PROVIDER_ID);
    }

    private static Config.TimingNodeConfig node(
            String id,
            Path path) {
        return new Config.TimingNodeConfig(
                new NodeId(id),
                path,
                TagProcessingPolicy.defaults());
    }

    private static Path path(
            String fileName) {
        return Paths.get(
                "target",
                "registry-test",
                fileName);
    }
}
