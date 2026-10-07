package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RuntimeCharacterizationHarnessTest {
    @Rule
    public TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void burstWorkloadUsesSimulatedInputAndCommitsMeasuredRecords()
            throws Exception {
        Path base =
                temporaryFolder
                        .newFolder("characterization")
                        .toPath();
        CharacterizationConfig config =
                CharacterizationConfig.parse(
                        new String[] {
                            "--workload", "burst",
                            "--work-dir", base.resolve("work").toString(),
                            "--output", base.resolve("evidence.json").toString(),
                            "--warmup-count", "1",
                            "--measured-count", "3",
                            "--rate", "20",
                            "--query-limit", "10"
                        });

        RuntimeCharacterizationHarness.Result result;
        try (RuntimeCharacterizationHarness harness =
                new RuntimeCharacterizationHarness(
                        config)) {
            result =
                    harness.run();
        }

        assertEquals(
                3L,
                result.nodeAfter().timingDataCommitCount()
                        - result.nodeBefore().timingDataCommitCount());
        assertEquals(
                3L,
                result.tagAfter().observations()
                        - result.tagBefore().observations());
        assertEquals(
                3L,
                result.tagAfter().mapped()
                        - result.tagBefore().mapped());
        assertEquals(
                0L,
                result.tagAfter().queueFull()
                        - result.tagBefore().queueFull());
        assertEquals(
                5,
                result.query().totalHistory());
        assertTrue(
                Files.exists(
                        config.workDirectory()
                                .resolve("timing-data.jsonl")));
    }

    @Test
    public void largerBurstSettlesWithoutRequiringEveryObservationToCommit()
            throws Exception {
        Path base =
                temporaryFolder
                        .newFolder("characterization-large-burst")
                        .toPath();
        CharacterizationConfig config =
                CharacterizationConfig.parse(
                        new String[] {
                            "--workload", "burst",
                            "--work-dir", base.resolve("work").toString(),
                            "--output", base.resolve("evidence.json").toString(),
                            "--warmup-count", "20",
                            "--measured-count", "100",
                            "--rate", "20",
                            "--query-limit", "10"
                        });

        RuntimeCharacterizationHarness.Result result;
        try (RuntimeCharacterizationHarness harness =
                new RuntimeCharacterizationHarness(
                        config)) {
            result =
                    harness.run();
        }

        long observationDelta =
                result.tagAfter().observations()
                        - result.tagBefore().observations();
        long committedDelta =
                result.nodeAfter().timingDataCommitCount()
                        - result.nodeBefore().timingDataCommitCount();

        assertEquals(
                100L,
                observationDelta);
        assertTrue(
                "bounded burst handling may commit at most the observations offered during measurement",
                committedDelta <= observationDelta);
    }

    @Test
    public void historyWorkloadRequiresExplicitPreload() {
        try {
            CharacterizationConfig.parse(
                    new String[] {
                        "--workload", "history"
                    });
            throw new AssertionError(
                    "history workload without preload must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    expected.getMessage()
                            .contains("requires --preload"));
        }
    }
}
