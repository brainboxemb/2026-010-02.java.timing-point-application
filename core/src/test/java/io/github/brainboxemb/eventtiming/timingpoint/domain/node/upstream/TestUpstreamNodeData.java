package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

import java.util.Collections;
import java.util.List;

/** Data-only fake for TIMING_DATA protocol tests. */
final class TestUpstreamNodeData implements UpstreamNodeData {
    @Override
    public long timingDataHead() {
        return 0L;
    }

    @Override
    public List<TimingData> timingDataRange(long fromSequence, int limit) {
        return Collections.emptyList();
    }
}
