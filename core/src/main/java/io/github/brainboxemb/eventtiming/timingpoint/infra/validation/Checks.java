package io.github.brainboxemb.eventtiming.timingpoint.infra.validation;

import java.util.Locale;

/**
 * Compact programming-contract checks used to keep normal control flow readable.
 */
public final class Checks {
    private Checks() {
    }

    public static void checkState(
            boolean condition,
            String message,
            Object... arguments) {
        if (!condition) {
            throw new IllegalStateException(
                    format(
                            message,
                            arguments));
        }
    }

    public static void checkArgument(
            boolean condition,
            String message,
            Object... arguments) {
        if (!condition) {
            throw new IllegalArgumentException(
                    format(
                            message,
                            arguments));
        }
    }

    private static String format(
            String message,
            Object... arguments) {
        if (arguments == null
                || arguments.length == 0) {
            return message;
        }
        return String.format(
                Locale.ROOT,
                message,
                arguments);
    }
}
