package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Parsed engineering workload parameters for one characterization invocation. */
final class CharacterizationOptions {

    enum Workload {
        STEADY,
        BURST,
        HISTORY;

        static Workload parse(String value) {
            if (value == null) {
                throw new IllegalArgumentException("workload must not be null");
            }
            return valueOf(value.trim().toUpperCase());
        }
    }

    private final Workload workload;
    private final int observations;
    private final double ratePerSecond;
    private final int warmupObservations;
    private final int preloadedHistory;
    private final int repetitions;
    private final Path evidenceDirectory;

    CharacterizationOptions(
            Workload workload,
            int observations,
            double ratePerSecond,
            int warmupObservations,
            int preloadedHistory,
            int repetitions,
            Path evidenceDirectory) {
        if (workload == null) {
            throw new IllegalArgumentException("workload must not be null");
        }
        if (observations < 1) {
            throw new IllegalArgumentException("observations must be positive");
        }
        if (!(ratePerSecond > 0.0d)) {
            throw new IllegalArgumentException("ratePerSecond must be positive");
        }
        if (warmupObservations < 0) {
            throw new IllegalArgumentException("warmupObservations must not be negative");
        }
        if (preloadedHistory < 0) {
            throw new IllegalArgumentException("preloadedHistory must not be negative");
        }
        if (repetitions < 1) {
            throw new IllegalArgumentException("repetitions must be positive");
        }
        if (evidenceDirectory == null) {
            throw new IllegalArgumentException("evidenceDirectory must not be null");
        }

        this.workload = workload;
        this.observations = observations;
        this.ratePerSecond = ratePerSecond;
        this.warmupObservations = warmupObservations;
        this.preloadedHistory = preloadedHistory;
        this.repetitions = repetitions;
        this.evidenceDirectory = evidenceDirectory;
    }

    static CharacterizationOptions parse(String[] args) {
        Workload workload = Workload.STEADY;
        int observations = 100;
        double ratePerSecond = 20.0d;
        int warmup = 20;
        int history = 0;
        int repetitions = 1;
        String configuredEvidence =
                System.getProperty(
                        "eventTiming.characterizationEvidenceDir",
                        "target/evidence");
        Path evidenceDirectory =
                Paths.get(configuredEvidence);

        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if ("--help".equals(option) || "-h".equals(option)) {
                throw new HelpRequested();
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException(
                        "missing value for " + option);
            }
            String value = args[++index];

            switch (option) {
                case "--workload":
                    workload = Workload.parse(value);
                    break;
                case "--observations":
                    observations = Integer.parseInt(value);
                    break;
                case "--rate":
                    ratePerSecond = Double.parseDouble(value);
                    break;
                case "--warmup":
                    warmup = Integer.parseInt(value);
                    break;
                case "--history":
                    history = Integer.parseInt(value);
                    break;
                case "--repetitions":
                    repetitions = Integer.parseInt(value);
                    break;
                case "--evidence-dir":
                    evidenceDirectory = Paths.get(value);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unknown option " + option);
            }
        }

        if (workload == Workload.HISTORY && history == 0) {
            history = 1000;
        }

        return new CharacterizationOptions(
                workload,
                observations,
                ratePerSecond,
                warmup,
                history,
                repetitions,
                evidenceDirectory);
    }

    static String help() {
        return String.join(
                System.lineSeparator(),
                "Runtime characterization options:",
                "  --workload steady|burst|history",
                "  --observations <count>       measured observations (default 100)",
                "  --rate <per-second>          aggregate steady/history rate (default 20)",
                "  --warmup <count>             warm-up observations (default 20)",
                "  --history <count>            preloaded committed records (history default 1000)",
                "  --repetitions <count>        retained runs (default 1)",
                "  --evidence-dir <path>        JSON evidence directory",
                "  --help");
    }

    Workload workload() { return workload; }
    int observations() { return observations; }
    double ratePerSecond() { return ratePerSecond; }
    int warmupObservations() { return warmupObservations; }
    int preloadedHistory() { return preloadedHistory; }
    int repetitions() { return repetitions; }
    Path evidenceDirectory() { return evidenceDirectory; }

    static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
