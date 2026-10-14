package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandQueueResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Narrow external access boundary for one TimingNode LogBook.
 *
 * <p>The accessor exposes intentional command/query/event operations without
 * exposing LogBook lifecycle, lane mechanics, startup-loading internals or
 * processor metrics.</p>
 */
public interface LogBookAccessor {
    /** Executes one command in Data Lane order and waits for its result. */
    <R> R execute(LogBookCommand<R> command);

    /**
     * Queues one command for later Data Lane execution and returns immediately.
     *
     * <p>{@code QUEUED} means waiting to run, not completed.</p>
     */
    CommandQueueResult queue(LogBookCommand<?> command);

    /** Executes one ordered query and waits for its result. */
    <R> R query(LogBookQuery<R> query);

    <R> R query(
            LogBookQuery<R> query,
            ReadConsistency consistency);

    EventSource<Status> statusChangedEvent();

    EventSource<TimingData> newDataEntryEvent();
}
