package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Intentional access boundary for one TimingNode LogBook.
 *
 * <p>Internal and external consumers use the same typed operation/event
 * contract. {@code execute(...)} submits a command without a caller result;
 * {@code process(...)} handles a command or query and returns its result.
 * The accessor does not expose LogBook lifecycle, lane mechanics,
 * startup-loading internals or engineering metrics.</p>
 */
public interface LogBookAccessor {
    /** Processes one command in LogBook order and returns its domain result. */
    <R> R process(LogBookCommand<R> command);

    /**
     * Executes one command asynchronously and returns after it is queued.
     *
     * <p>Like {@code Executor.execute}, queue rejection propagates immediately
     * as RejectedExecutionException; later command execution has no caller result.</p>
     */
    void execute(LogBookCommand<?> command);

    /** Processes one query with ORDERED consistency and returns its result. */
    <R> R process(LogBookQuery<R> query);

    /** Processes one query with the explicitly requested consistency model. */
    <R> R process(LogBookQuery<R> query, ReadConsistency consistency);

    /** Publishes the completed LogBook startup recovery result. */
    EventSource<LogBookRecoveryStatus> recoveryStatusChangedEvent();

    /** Publishes each TimingData record after a successful durable commit. */
    EventSource<TimingData> newDataEntryEvent();
}
