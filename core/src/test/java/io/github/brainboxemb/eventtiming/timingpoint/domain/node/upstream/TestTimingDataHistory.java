package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TimingDataHistory;

import java.util.Collections;
import java.util.List;

/** Data-only fake for TIMING_DATA history protocol tests. */
final class TestTimingDataHistory implements TimingDataHistory {
    @Override
    public long timingDataHead() {
        return 0L;
    }

    @Override
    public List<TimingData> timingDataRange(long fromSequence, int limit) {
        return Collections.emptyList();
    }
}
