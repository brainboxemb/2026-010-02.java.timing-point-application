package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Intentional access boundary for one TimingNode LogBook.
 *
 * <p>Internal and external consumers use the same typed command/query/event
 * contract. The accessor does not expose LogBook lifecycle, lane mechanics,
 * startup-loading internals or engineering metrics.</p>
 */
public interface LogBookAccessor {
    /** Processes one command in LogBook order and returns its domain result. */
    <R> R process(LogBookCommand<R> command);

    /**
     * Queues one command for later Data Lane execution and returns immediately.
     *
     * <p>Rejection propagates as the standard execution exception from the owning lane.</p>
     */
    void queue(LogBookCommand<?> command);

    /** Executes one ordered query and waits for its result. */
    <R> R query(LogBookQuery<R> query);

    /** Reads one query with the explicitly requested consistency model. */
    <R> R query(
            LogBookQuery<R> query,
            ReadConsistency consistency);

    /** Publishes the first available status and later effective status changes. */
    EventSource<Status> statusChangedEvent();

    /** Publishes each TimingData record after a successful durable commit. */
    EventSource<TimingData> newDataEntryEvent();
}
