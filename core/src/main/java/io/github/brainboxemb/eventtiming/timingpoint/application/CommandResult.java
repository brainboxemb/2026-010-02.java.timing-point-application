package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Result of a presentation-triggered TimingRecord command.
 *
 * <p>This type contains normal application outcomes only. Technical LogBook
 * execution failures abort normal flow and are diagnosed by the owning
 * execution boundary instead of being repeated as another public state model.</p>
 */
public final class CommandResult {

    /** Normal outcome of a presentation-triggered TimingRecord command. */
    public enum Outcome {
        /** The command durably committed one TimingRecord record. */
        COMMITTED,

        /** The command was not applied because the TimingNode is not OPEN. */
        NODE_NOT_OPEN
    }

    private final Outcome outcome;
    private final TimingRecord timingRecord;

    /** Creates one immutable command result. */
    private CommandResult(Outcome outcome, TimingRecord timingData) {

        this.outcome = outcome;
        this.timingData = timingData;
    }

    /** Creates a successful result carrying the committed TimingRecord record. */
    public static CommandResult committed(TimingRecord timingData) {

        failIfInvalidArgument(timingData != null, "timingData must not be null");
        return new CommandResult(Outcome.COMMITTED, timingData);
    }

    /** Creates a normal rejection because the TimingNode is not OPEN. */
    public static CommandResult nodeNotOpen() {

        return new CommandResult(Outcome.NODE_NOT_OPEN, null);
    }

    /** Returns the normal command outcome. */
    public Outcome outcome() {

        return outcome;
    }

    /** Reports whether the command committed TimingRecord. */
    public boolean committed() {

        return outcome == Outcome.COMMITTED;
    }

    /**
     * Returns the committed TimingRecord.
     *
     * @throws IllegalStateException when {@link #outcome()} is not
     *         {@link Outcome#COMMITTED}
     */
    public TimingRecord timingData() {

        failIfIllegalState(timingData != null, "command did not commit TimingRecord");
        return timingData;
    }
}
