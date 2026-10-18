package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;

import java.util.concurrent.CompletableFuture;

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
    /** Returns the current LogBook availability status without entering the Data Lane. */
    LogBookStatus status();

    /**
     * Processes one command in LogBook order.
     *
     * <p>This call blocks until the Data Lane returns the command result or the operation fails.</p>
     *
     */
    <R> R process(LogBookCommand<R> command);

    /**
     * Executes one command asynchronously and returns after queueing.
     *
     * <p>Queue rejection and later execution failure are LogBook diagnostics;
     * this fire-and-forget path has no caller result.</p>
     */
    void execute(LogBookCommand<?> command);

    /**
     * Queues one ORDERED query and returns its completion without waiting.
     *
     * <p>This is the non-blocking form used by YieldingTasks that must release
     * the shared physical worker while waiting for Data Lane work.</p>
     */
    <R> CompletableFuture<R> submit(LogBookQuery<R> query);

    /**
     * Processes one ORDERED query.
     *
     * <p>This call blocks until earlier queued Data Lane work has completed and the query returns.</p>
     *
     */
    <R> R process(LogBookQuery<R> query);

    /**
     * Processes a query with the requested consistency.
     *
     * <p>ORDERED may block on the Data Lane. CURRENT reads the published snapshot directly
     * and does not wait for queued Data Lane work.</p>
     *
     */
    <R> R process(LogBookQuery<R> query, ReadConsistency consistency);

}
