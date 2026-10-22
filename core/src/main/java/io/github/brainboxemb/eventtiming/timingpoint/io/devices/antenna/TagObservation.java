package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.eventdata.TagId;

/**
 * One immutable decoded antenna observation.
 *
 * <p>The antenna/I-O boundary supplies two independent time values. The epoch
 * millisecond value is the absolute event time that can later become TimingData.
 * The monotonic nanosecond value exists only for elapsed-time decisions such as
 * passage gaps and must never be persisted as event time.</p>
 */
public final class TagObservation {
    private final TagId tagId;
    private final int rssi;
    private final long observedAtEpochMillis;
    private final long observedAtMonotonicNanos;

    /** Creates one decoded observation from the two time bases captured at the antenna boundary. */
    public TagObservation(
            TagId tagId,
            int rssi,
            long observedAtEpochMillis,
            long observedAtMonotonicNanos) {
        if (tagId == null) {
            throw new IllegalArgumentException("tagId must not be null");
        }
        this.tagId = tagId;
        this.rssi = rssi;
        this.observedAtEpochMillis = observedAtEpochMillis;
        this.observedAtMonotonicNanos = observedAtMonotonicNanos;
    }

    /** Returns the observed TagId. */
    public TagId tagId() {
        return tagId;
    }

    /** Returns the antenna-provided RSSI for this observation. */
    public int rssi() {
        return rssi;
    }

    /** Returns the absolute antenna observation time as UTC epoch milliseconds. */
    public long observedAtEpochMillis() {
        return observedAtEpochMillis;
    }

    /** Returns the process-local monotonic time captured for this antenna observation. */
    public long observedAtMonotonicNanos() {
        return observedAtMonotonicNanos;
    }

}
