package oss.relay.eventtiming.timingpoint.infra.validation;

import java.util.Locale;

/**
 * Always-enabled programming-contract failure helpers. Callers pass positive assertions: true means the contract holds.
 */
public final class Checks {

    /** Prevents construction of this stateless validation utility. */
    private Checks() {
    }

    /** Fails with IllegalStateException when the required-state assertion is false. */
    public static void failIfIllegalState(boolean assertion, String message, Object... arguments) {
        if (!assertion) {
            throw new IllegalStateException(format(message, arguments));
        }
    }

    /** Fails with IllegalArgumentException when the argument-contract assertion is false. */
    public static void failIfInvalidArgument(boolean assertion, String message, Object... arguments) {
        if (!assertion) {
            throw new IllegalArgumentException(format(message, arguments));
        }
    }

    /** Formats a contract-failure message using locale-independent formatting. */
    private static String format(String message, Object... arguments) {
        if (arguments == null || arguments.length == 0) {
            return message;
        }
        return String.format(Locale.ROOT, message, arguments);
    }
}
