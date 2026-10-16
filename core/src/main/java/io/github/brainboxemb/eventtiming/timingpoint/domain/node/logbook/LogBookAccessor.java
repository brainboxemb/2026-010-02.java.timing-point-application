package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
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
    /**
     * Processes one command in LogBook order.
     *
     * <p>This call blocks until the Data Lane returns the command result or the operation fails.</p>
     *
     * @throws OperationException when the ordered operation cannot return its normal result
     */
    <R> R process(LogBookCommand<R> command) throws OperationException;

    /**
     * Executes one command asynchronously and returns after queueing.
     *
     * <p>Queue rejection and later execution failure are LogBook diagnostics;
     * this fire-and-forget path has no caller result.</p>
     */
    void execute(LogBookCommand<?> command);

    /**
     * Processes one ORDERED query.
     *
     * <p>This call blocks until earlier queued Data Lane work has completed and the query returns.</p>
     *
     * @throws OperationException when the ordered query cannot return its normal result
     */
    <R> R process(LogBookQuery<R> query) throws OperationException;

    /**
     * Processes a query with the requested consistency.
     *
     * <p>ORDERED may block on the Data Lane. CURRENT reads the published snapshot directly
     * and does not wait for queued Data Lane work.</p>
     *
     * @throws OperationException when the requested query cannot return its normal result
     */
    <R> R process(LogBookQuery<R> query, ReadConsistency consistency) throws OperationException;

    /** Returns the recovery event; listeners are invoked synchronously on the LogBook Data Lane. */
    EventSource<LogBookRecoveryStatus> recoveryStatusChangedEvent();

    /** Returns the post-commit event; listeners are invoked synchronously on the LogBook Data Lane. */
    EventSource<TimingData> newDataEntryEvent();
}
