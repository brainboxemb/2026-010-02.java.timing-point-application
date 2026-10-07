package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.nio.file.Path;

/** CLI entry point for the optional runtime-characterization Maven profile. */
public final class RuntimeCharacterizationMain {
    private RuntimeCharacterizationMain() {
    }

    public static void main(
            String[] args)
            throws Exception {
        final CharacterizationOptions options;
        try {
            options =
                    CharacterizationOptions.parse(
                            args);
        } catch (CharacterizationOptions.HelpRequested help) {
            System.out.println(
                    CharacterizationOptions.help());
            return;
        }

        RuntimeCharacterization harness =
                new RuntimeCharacterization();

        for (int repetition = 1;
                repetition <= options.repetitions();
                repetition++) {
            Path evidence =
                    harness.runOne(
                            options,
                            repetition);
            System.out.println(
                    "Runtime characterization evidence: "
                            + evidence.toAbsolutePath());
        }
    }
}
