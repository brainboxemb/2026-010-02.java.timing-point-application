package io.github.brainboxemb.eventtiming.timingpoint.infra.validation;

import java.util.Locale;

/**
 * Compact programming-contract checks used to keep normal control flow readable.
 */
public final class Checks {

    /** Prevents construction of this stateless validation utility. */
    private Checks() {
    }

    /** Throws IllegalStateException when the required program state is not present. */
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

    /** Throws IllegalArgumentException when a supplied argument violates its contract. */
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

    /** Formats a contract-check message using locale-independent formatting. */
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
