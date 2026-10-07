package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertTrue;

public class RuntimeCharacterizationTest {

    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void tinyBurstUsesProductInputPathAndWritesEvidence()
            throws Exception {
        Path evidenceDirectory =
                temporaryFolder
                        .newFolder("evidence")
                        .toPath();
        CharacterizationOptions options =
                new CharacterizationOptions(
                        CharacterizationOptions.Workload.BURST,
                        2,
                        20.0d,
                        1,
                        1,
                        1,
                        evidenceDirectory);

        Path evidence =
                new RuntimeCharacterization()
                        .runOne(
                                options,
                                1);

        String json =
                new String(
                        Files.readAllBytes(
                                evidence),
                        StandardCharsets.UTF_8);

        assertTrue(
                json.contains(
                        "\"outcome\" : \"PASS\""));
        assertTrue(
                json.contains(
                        "\"observations\" : 2"));
        assertTrue(
                json.contains(
                        "\"commitCountDelta\" : 2"));
        assertTrue(
                Files.exists(
                        evidenceDirectory
                                .resolve("work-r1")
                                .resolve("timing-data.jsonl")));
    }
}
