package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Small guard for execution boundaries that handle queue rejection explicitly.
 *
 * <p>The guarded operation and component-owned rejection policy remain visible
 * at the call site while repeated {@link RejectedExecutionException}
 * try/catch boilerplate stays in this utility. Other runtime failures are not
 * treated as queue rejection.</p>
 */
public final class SafeExecute {

    /** Prevents construction of this stateless execution utility. */
    private SafeExecute() {
    }

    /**
     * Runs one operation and converts execution rejection to a component failure.
     *
     * <p>The supplied cleanup runs only after {@link RejectedExecutionException}.
     * Other runtime failures propagate unchanged.</p>
     */
    public static void run(
            Runnable operation,
            Runnable cleanup,
            String message) {
        checkArgument(operation != null, "operation must not be null");
        checkArgument(cleanup != null, "cleanup must not be null");
        checkArgument(message != null && !message.trim().isEmpty(),
                "message must not be blank");

        try {
            operation.run();
        } catch (RejectedExecutionException ex) {
            cleanup.run();
            throw new IllegalStateException(
                    message,
                    ex);
        }
    }

    /**
     * Runs one operation and delegates queue rejection to the caller's policy.
     *
     * <p>Only RejectedExecutionException is handled. Other RuntimeExceptions
     * propagate unchanged. The caller owns any warning, metric or recovery
     * decision; the execution guard owns no component-specific policy.</p>
     */
    public static void runOrHandleRejection(
            Runnable operation,
            Consumer<RejectedExecutionException> onRejection) {
        checkArgument(operation != null, "operation must not be null");
        checkArgument(onRejection != null, "onRejection must not be null");

        try {
            operation.run();
        } catch (RejectedExecutionException ex) {
            onRejection.accept(ex);
        }
    }

}
