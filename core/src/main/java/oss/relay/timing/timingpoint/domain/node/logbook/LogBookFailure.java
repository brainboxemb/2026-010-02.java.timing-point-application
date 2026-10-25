package oss.relay.timing.timingpoint.domain.node.logbook;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Immutable description of one failed LogBook command. */
public final class LogBookFailure {
    public final String operation;
    public final String message;

    LogBookFailure(String operation, Throwable failure) {
        failIfInvalidArgument(
                operation != null && !operation.trim().isEmpty(),
                "operation must not be blank");
        failIfInvalidArgument(failure != null, "failure must not be null");

        this.operation = operation.trim();
        String detail = failure.getMessage();
        this.message =
                detail == null || detail.trim().isEmpty()
                        ? failure.getClass().getSimpleName()
                        : detail.trim();
    }
}
