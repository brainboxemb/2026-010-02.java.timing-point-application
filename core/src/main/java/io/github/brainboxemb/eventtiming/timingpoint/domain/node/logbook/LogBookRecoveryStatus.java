package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Result of one LogBook startup recovery attempt.
 *
 * <p>A successful result includes the committed TimingData head. Consumers can
 * use that scalar to decide which history they need to read without receiving
 * the complete recovered record list in the recovery event.</p>
 */
public final class LogBookRecoveryStatus {
    private final boolean repairedIncompleteTail;
    private final long timingDataHead;
    private final String failureMessage;

    private LogBookRecoveryStatus(
            boolean repairedIncompleteTail,
            long timingDataHead,
            String failureMessage) {

        this.repairedIncompleteTail = repairedIncompleteTail;
        this.timingDataHead = timingDataHead;
        this.failureMessage = failureMessage;
    }

    public static LogBookRecoveryStatus success(
            boolean repairedIncompleteTail,
            long timingDataHead) {

        failIfInvalidArgument(timingDataHead >= 0L, "timingDataHead must not be negative");
        return new LogBookRecoveryStatus(
                repairedIncompleteTail,
                timingDataHead,
                null);
    }

    public static LogBookRecoveryStatus failed(String failureMessage) {
        failIfInvalidArgument(
                failureMessage != null && !failureMessage.trim().isEmpty(),
                "failureMessage must not be blank");
        return new LogBookRecoveryStatus(false, 0L, failureMessage.trim());
    }

    /** Reports whether startup repaired an incomplete final persisted record. */
    public boolean repairedIncompleteTail() {
        return repairedIncompleteTail;
    }

    /** Returns the recovered committed TimingData head. */
    public long timingDataHead() {
        failIfIllegalState(!failed(), "failed recovery has no TimingData head");
        return timingDataHead;
    }

    public boolean failed() {
        return failureMessage != null;
    }

    public String failureMessage() {
        failIfIllegalState(failed(), "recovery did not fail");
        return failureMessage;
    }
}
