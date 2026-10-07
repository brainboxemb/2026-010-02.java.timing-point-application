package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Immutable command-line workload definition for one characterization run. */
final class CharacterizationConfig {
    enum Workload {
        STEADY,
        BURST,
        HISTORY;

        static Workload parse(String value) {
            for (Workload workload : values()) {
                if (workload.name().equalsIgnoreCase(value)) {
                    return workload;
                }
            }
            throw new IllegalArgumentException(
                    "workload must be steady, burst or history");
        }
    }

    private final Workload workload;
    private final Path workDirectory;
    private final Path output;
    private final int warmupCount;
    private final int measuredCount;
    private final int registrationsPerSecond;
    private final int preloadCount;
    private final int queryLimit;

    private CharacterizationConfig(
            Workload workload,
            Path workDirectory,
            Path output,
            int warmupCount,
            int measuredCount,
            int registrationsPerSecond,
            int preloadCount,
            int queryLimit) {
        this.workload = workload;
        this.workDirectory = workDirectory;
        this.output = output;
        this.warmupCount = warmupCount;
        this.measuredCount = measuredCount;
        this.registrationsPerSecond = registrationsPerSecond;
        this.preloadCount = preloadCount;
        this.queryLimit = queryLimit;
    }

    static CharacterizationConfig parse(String[] args) {
        Workload workload = Workload.STEADY;
        Path workDirectory =
                Paths.get("target", "runtime-characterization", "work");
        Path output =
                Paths.get("target", "runtime-characterization", "evidence.json");
        int warmupCount = 20;
        int measuredCount = 100;
        int registrationsPerSecond = 20;
        int preloadCount = 0;
        int queryLimit = 100;

        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if ("--workload".equals(option)) {
                workload = Workload.parse(value(args, ++index, option));
            } else if ("--work-dir".equals(option)) {
                workDirectory = Paths.get(value(args, ++index, option));
            } else if ("--output".equals(option)) {
                output = Paths.get(value(args, ++index, option));
            } else if ("--warmup-count".equals(option)) {
                warmupCount = positiveInt(value(args, ++index, option), option);
            } else if ("--measured-count".equals(option)) {
                measuredCount = positiveInt(value(args, ++index, option), option);
            } else if ("--rate".equals(option)) {
                registrationsPerSecond = positiveInt(value(args, ++index, option), option);
            } else if ("--preload".equals(option)) {
                preloadCount = nonNegativeInt(value(args, ++index, option), option);
            } else if ("--query-limit".equals(option)) {
                queryLimit = positiveInt(value(args, ++index, option), option);
            } else {
                throw new IllegalArgumentException(
                        "Unknown option: " + option);
            }
        }

        if (workload == Workload.HISTORY
                && preloadCount == 0) {
            throw new IllegalArgumentException(
                    "history workload requires --preload > 0");
        }

        return new CharacterizationConfig(
                workload,
                workDirectory,
                output,
                warmupCount,
                measuredCount,
                registrationsPerSecond,
                preloadCount,
                queryLimit);
    }

    private static String value(
            String[] args,
            int index,
            String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(
                    "Missing value for " + option);
        }
        return args[index];
    }

    private static int positiveInt(
            String value,
            String option) {
        int parsed = nonNegativeInt(value, option);
        if (parsed < 1) {
            throw new IllegalArgumentException(
                    option + " must be positive");
        }
        return parsed;
    }

    private static int nonNegativeInt(
            String value,
            String option) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(
                        option + " must not be negative");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    option + " must be an integer",
                    ex);
        }
    }

    Workload workload() { return workload; }
    Path workDirectory() { return workDirectory; }
    Path output() { return output; }
    int warmupCount() { return warmupCount; }
    int measuredCount() { return measuredCount; }
    int registrationsPerSecond() { return registrationsPerSecond; }
    int preloadCount() { return preloadCount; }
    int queryLimit() { return queryLimit; }
}
