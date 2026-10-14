package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

import java.util.List;

/**
 * Narrow Data Lane access required by node-scoped upstream TIMING_DATA sync.
 *
 * <p>The Task Lane never receives the mutable LogBook. The published scalar head
 * may be read directly; bounded history reads execute on the Data Lane.</p>
 */
public interface UpstreamNodeData {
    long timingDataHead();

    List<TimingData> timingDataRange(long fromSequence, int limit);
}
