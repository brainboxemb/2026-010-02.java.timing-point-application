package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.nio.file.Paths;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CharacterizationOptionsTest {

    @Test
    public void parsesExplicitWorkloadParameters() {
        CharacterizationOptions options =
                CharacterizationOptions.parse(
                        new String[]{
                                "--workload", "history",
                                "--observations", "12",
                                "--rate", "7.5",
                                "--warmup", "3",
                                "--history", "40",
                                "--repetitions", "2",
                                "--evidence-dir", "build/evidence"
                        });

        assertEquals(
                CharacterizationOptions.Workload.HISTORY,
                options.workload());
        assertEquals(
                12,
                options.observations());
        assertEquals(
                7.5d,
                options.ratePerSecond(),
                0.0d);
        assertEquals(
                3,
                options.warmupObservations());
        assertEquals(
                40,
                options.preloadedHistory());
        assertEquals(
                2,
                options.repetitions());
        assertEquals(
                Paths.get("build/evidence"),
                options.evidenceDirectory());
    }
}
