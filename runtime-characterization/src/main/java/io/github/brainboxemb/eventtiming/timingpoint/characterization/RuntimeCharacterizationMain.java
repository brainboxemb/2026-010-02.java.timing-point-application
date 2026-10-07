package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.time.Instant;

/** Command-line entrypoint for engineering runtime characterization. */
public final class RuntimeCharacterizationMain {
    private RuntimeCharacterizationMain() {
    }

    public static void main(String[] args)
            throws Exception {
        CharacterizationConfig config;
        try {
            config =
                    CharacterizationConfig.parse(
                            args);
        } catch (IllegalArgumentException ex) {
            System.err.println(
                    ex.getMessage());
            System.err.println(
                    usage());
            throw ex;
        }

        CharacterizationBuildInfo build =
                CharacterizationBuildInfo.load();
        Instant startedAt =
                Instant.now();

        try (RuntimeCharacterizationHarness harness =
                new RuntimeCharacterizationHarness(
                        config)) {
            RuntimeCharacterizationHarness.Result result =
                    harness.run();
            CharacterizationEvidenceWriter.writeSuccess(
                    config,
                    build,
                    result);
            System.out.println(
                    "Runtime characterization evidence: "
                            + config.output()
                                    .toAbsolutePath()
                                    .normalize());
        } catch (Exception ex) {
            CharacterizationEvidenceWriter.writeFailure(
                    config,
                    build,
                    startedAt,
                    ex);
            throw ex;
        }
    }

    static String usage() {
        return "Usage: runtime-characterization "
                + "[--workload steady|burst|history] "
                + "[--work-dir <path>] "
                + "[--output <json>] "
                + "[--warmup-count <n>] "
                + "[--measured-count <n>] "
                + "[--rate <registrations-per-second>] "
                + "[--preload <records>] "
                + "[--query-limit <n>]";
    }
}
