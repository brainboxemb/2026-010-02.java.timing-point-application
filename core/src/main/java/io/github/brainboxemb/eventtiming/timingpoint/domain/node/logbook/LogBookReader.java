package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

import java.util.List;

/**
 * Read-only port for committed LogBook contents.
 *
 * <p>The LogBook component owns this contract. Consumers may read the safely
 * published source head directly; bounded record ranges must be requested while
 * the owning Data Lane is current.</p>
 */
public interface LogBookReader {
    long timingDataHead();

    List<TimingData> timingDataRange(long fromSequence, int limit);
}
