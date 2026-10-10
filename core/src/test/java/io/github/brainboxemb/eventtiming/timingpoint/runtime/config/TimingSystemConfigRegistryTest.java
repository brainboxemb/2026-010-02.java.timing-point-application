package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class TimingSystemConfigRegistryTest {
    @Test
    public void singleSystemCompatibilityConfigUsesCompactDefaultId() {
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
