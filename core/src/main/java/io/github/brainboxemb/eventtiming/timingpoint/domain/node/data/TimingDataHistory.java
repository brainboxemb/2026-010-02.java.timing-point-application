package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

import java.util.List;

/**
 * Read-side access to committed TimingData history.
 *
 * <p>This contract belongs to the node data component. Consumers such as
 * upstream synchronization may depend on it, but the data component has no
 * knowledge of those consumers.</p>
 */
public interface TimingDataHistory {
    /** Returns the latest committed sequence, or 0 when the LogBook is empty. */
    long timingDataHead();

    /**
     * Returns at most {@code limit} committed records from {@code fromSequence}.
     *
     * <p>The caller must execute this operation on the owning Data Lane.</p>
     */
    List<TimingData> timingDataRange(long fromSequence, int limit);
}
