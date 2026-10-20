package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/** Result of one presentation-triggered LogBook command. */
public final class CommandResult {

    public enum Outcome {
        COMMITTED,
        NODE_NOT_OPEN
    }

    private final Outcome outcome;
    private final TimingRecord timingRecord;

    private CommandResult(Outcome outcome, TimingRecord timingRecord) {
        this.outcome = outcome;
        this.timingRecord = timingRecord;
    }

    public static CommandResult committed(TimingRecord timingRecord) {
        failIfInvalidArgument(timingRecord != null, "timingRecord must not be null");
        return new CommandResult(Outcome.COMMITTED, timingRecord);
    }

    public static CommandResult nodeNotOpen() {
        return new CommandResult(Outcome.NODE_NOT_OPEN, null);
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean committed() {
        return outcome == Outcome.COMMITTED;
    }

    /** Returns the committed record. */
    public TimingRecord timingRecord() {
        failIfIllegalState(timingRecord != null, "command did not commit a timing record");
        return timingRecord;
    }
}
