package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.CompactTimingTime;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

/** One immutable decoded tag observation. */
public final class TagObservation {
    private final TagId tagId;
    private final int rssi;
    private final long observedAt;

    /** Creates one immutable decoded observation from the antenna source. */
    public TagObservation(
            TagId tagId,
            int rssi,
            TimingTimestamp observedAt) {
        if (tagId == null) {
            throw new IllegalArgumentException("tagId must not be null");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }
        this.tagId = tagId;
        this.rssi = rssi;
        this.observedAt = CompactTimingTime.fromTimestamp(observedAt);
    }

    /** Returns the observed TagId. */
    public TagId tagId() {
        return tagId;
    }

    /** Returns the antenna-provided RSSI for this observation. */
    public int rssi() {
        return rssi;
    }

    /** Returns the compact centisecond observation time used by the processing hot path. */
    public long observedAtValue() {
        return observedAt;
    }

    /** Returns the observation time assigned at the antenna/I-O boundary. */
    public TimingTimestamp observedAt() {
        return CompactTimingTime.toTimestamp(observedAt);
    }
}
