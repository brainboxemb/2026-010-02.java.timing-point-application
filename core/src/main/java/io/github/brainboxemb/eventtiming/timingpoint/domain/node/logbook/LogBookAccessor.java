package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Narrow external access boundary for one TimingNode LogBook.
 *
 * <p>The accessor exposes intentional command/query/event operations without
 * exposing LogBookProcessor lifecycle, lane mechanics, recovery hooks or
 * processor metrics.</p>
 */
public interface LogBookAccessor {
    <R> R invoke(LogBookCommand<R> command);

    CommandAdmission offer(LogBookCommand<?> command);

    <R> R query(LogBookQuery<R> query);

    <R> R query(
            LogBookQuery<R> query,
            ReadConsistency consistency);

    EventSource<Status> statusChangedEvent();

    EventSource<TimingData> newDataEntryEvent();
}
