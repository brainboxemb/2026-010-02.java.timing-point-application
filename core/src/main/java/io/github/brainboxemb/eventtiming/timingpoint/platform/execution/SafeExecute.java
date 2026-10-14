package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.RejectedExecutionException;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Small execution guard for call sites that need one standard rejection policy.
 *
 * <p>The guarded operation stays visible at the call site while the repeated
 * {@link RejectedExecutionException} catch-and-wrap boilerplate lives here.</p>
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
}
