package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * Result of a presentation-triggered TimingData command.
 *
 * <p>This type contains normal application outcomes only. Technical LogBook
 * execution failures abort normal flow and are diagnosed by the owning
 * execution boundary instead of being repeated as another public state model.</p>
 */
public final class CommandResult {

    /** Normal outcome of a presentation-triggered TimingData command. */
    public enum Outcome {
        /** The command durably committed one TimingData record. */
        COMMITTED,

        /** The command was not applied because the TimingNode is not OPEN. */
        NODE_NOT_OPEN
    }

    private final Outcome outcome;
    private final TimingData timingData;

    /** Creates one immutable command result. */
    private CommandResult(Outcome outcome, TimingData timingData) {

        this.outcome = outcome;
        this.timingData = timingData;
    }

    /** Creates a successful result carrying the committed TimingData record. */
    public static CommandResult committed(TimingData timingData) {

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

    /** Reports whether the command committed TimingData. */
    public boolean committed() {

        return outcome == Outcome.COMMITTED;
    }

    /**
     * Returns the committed TimingData.
     *
     * @throws IllegalStateException when {@link #outcome()} is not
     *         {@link Outcome#COMMITTED}
     */
    public TimingData timingData() {

        failIfIllegalState(timingData != null, "command did not commit TimingData");
        return timingData;
    }
}
