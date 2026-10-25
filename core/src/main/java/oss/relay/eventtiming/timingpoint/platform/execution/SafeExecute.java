package oss.relay.eventtiming.timingpoint.platform.execution;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Small guards for execution boundaries that handle expected runtime failures explicitly.
 *
 * <p>The guarded operation and component-owned failure policy remain visible at
 * the call site while repeated try/catch boilerplate stays in this utility.</p>
 */
public final class SafeExecute {

    /** Prevents construction of this stateless execution utility. */
    private SafeExecute() {
    }

    /**
     * Calls one operation and returns empty when the selected runtime failure is handled.
     *
     * <p>Failures of another type, or selected failures rejected by the handler
     * predicate, propagate unchanged.</p>
     */
    public static <T, E extends RuntimeException> Optional<T> callOrEmpty(
            Supplier<T> operation,
            Class<E> failureType,
            Predicate<E> handledFailure) {
        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(failureType != null, "failureType must not be null");
        failIfInvalidArgument(handledFailure != null, "handledFailure must not be null");

        try {
            return Optional.ofNullable(operation.get());
        } catch (RuntimeException ex) {
            if (!failureType.isInstance(ex)) {
                throw ex;
            }

            E failure = failureType.cast(ex);
            if (!handledFailure.test(failure)) {
                throw ex;
            }
            return Optional.empty();
        }
    }

    /**
     * Runs one operation and converts execution rejection to a component failure.
     *
     * <p>The supplied cleanup runs only after {@link RejectedExecutionException}.
     * Other runtime failures propagate unchanged.</p>
     */
    public static void run(Runnable operation, Runnable cleanup, String message) {
        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(cleanup != null, "cleanup must not be null");
        failIfInvalidArgument(message != null && !message.trim().isEmpty(), "message must not be blank");

        try {
            operation.run();
        } catch (RejectedExecutionException ex) {
            cleanup.run();
            throw new IllegalStateException(message, ex);
        }
    }

    /**
     * Runs one operation, lets the caller handle any runtime failure, then rethrows it unchanged.
     *
     * <p>The failure handler owns component-specific cleanup or recovery. This
     * wrapper only keeps repeated catch-and-rethrow mechanics out of the normal
     * component flow. If the handler does nothing, the original failure still
     * propagates to the caller.</p>
     */
    public static void runOrRethrow(Runnable operation, Consumer<RuntimeException> onFailure) {
        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(onFailure != null, "onFailure must not be null");

        try {
            operation.run();
        } catch (RuntimeException ex) {
            onFailure.accept(ex);
            throw ex;
        }
    }

    /**
     * Runs one operation and delegates queue rejection to the caller's policy.
     *
     * <p>Returns true when the operation completed without queue rejection and
     * false when RejectedExecutionException was handled. Other RuntimeExceptions
     * propagate unchanged. The caller owns any warning, metric or recovery
     * decision; the execution guard owns no component-specific policy.</p>
     */
    public static boolean runOrHandleRejection(
            Runnable operation,
            Consumer<RejectedExecutionException> onRejection) {
        failIfInvalidArgument(operation != null, "operation must not be null");
        failIfInvalidArgument(onRejection != null, "onRejection must not be null");

        try {
            operation.run();
            return true;
        } catch (RejectedExecutionException ex) {
            onRejection.accept(ex);
            return false;
        }
    }
}
