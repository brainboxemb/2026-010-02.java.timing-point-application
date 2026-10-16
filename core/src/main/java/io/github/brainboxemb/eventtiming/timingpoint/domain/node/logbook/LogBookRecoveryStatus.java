package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * Result of one LogBook startup recovery attempt.
 *
 * <p>This describes only LogBook recovery facts. It does not represent the
 * current OPEN/CLOSED state of the owning TimingNode.</p>
 */
public final class LogBookRecoveryStatus {
    private final boolean repairedIncompleteTail;
    private final String failureMessage;

    private LogBookRecoveryStatus(boolean repairedIncompleteTail, String failureMessage) {
        this.repairedIncompleteTail = repairedIncompleteTail;
        this.failureMessage = failureMessage;
    }

    public static LogBookRecoveryStatus success(boolean repairedIncompleteTail) {
        return new LogBookRecoveryStatus(repairedIncompleteTail, null);
    }

    public static LogBookRecoveryStatus failed(String failureMessage) {
        checkArgument(
                failureMessage != null && !failureMessage.trim().isEmpty(),
                "failureMessage must not be blank");
        return new LogBookRecoveryStatus(false, failureMessage.trim());
    }

    /** Reports whether startup repaired an incomplete final persisted record. */
    public boolean repairedIncompleteTail() {
        return repairedIncompleteTail;
    }

    public boolean failed() {
        return failureMessage != null;
    }

    public String failureMessage() {
        checkState(failed(), "recovery did not fail");
        return failureMessage;
    }
}
