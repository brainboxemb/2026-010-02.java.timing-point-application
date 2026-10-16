package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/** Immutable description of one failed LogBook command. */
public final class LogBookFailure {
    public final String operation;
    public final String message;

    LogBookFailure(String operation, Throwable failure) {
        checkArgument(
                operation != null && !operation.trim().isEmpty(),
                "operation must not be blank");
        checkArgument(failure != null, "failure must not be null");

        this.operation = operation.trim();
        String detail = failure.getMessage();
        this.message =
                detail == null || detail.trim().isEmpty()
                        ? failure.getClass().getSimpleName()
                        : detail.trim();
    }
}
