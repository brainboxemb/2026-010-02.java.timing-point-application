package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

/** One immutable decoded tag observation. */
public final class TagObservation {
    private final TagId tagId;
    private final int rssi;
    private final TimingTimestamp observedAt;

    public TagObservation(TagId tagId, int rssi, TimingTimestamp observedAt) {
        if (tagId == null) {
            throw new IllegalArgumentException("tagId must not be null");
        }
        if (observedAt == null) {
            throw new IllegalArgumentException("observedAt must not be null");
        }
        this.tagId = tagId;
        this.rssi = rssi;
        this.observedAt = observedAt;
    }

    public TagId tagId() {
        return tagId;
    }

    public int rssi() {
        return rssi;
    }

    public TimingTimestamp observedAt() {
        return observedAt;
    }
}
