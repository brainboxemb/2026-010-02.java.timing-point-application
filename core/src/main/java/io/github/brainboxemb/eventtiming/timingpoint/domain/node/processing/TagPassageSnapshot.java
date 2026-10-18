package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.CompactTimingTime;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.TagId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable engineering view of one currently aggregated registration passage.
 *
 * <p>This is diagnostic state, not committed TimingData.</p>
 */
public final class TagPassageSnapshot {

    /** Immutable per-TagId diagnostic statistics within one open passage. */
    public static final class TagStats {
        private final TagId tagId;
        private final long observationCount;
        private final int strongestRssi;
        private final long firstObservedAt;
        private final long lastObservedAt;

        /** Creates immutable diagnostic statistics for one TagId in the passage. */
        TagStats(
                TagId tagId,
                long observationCount,
                int strongestRssi,
                long firstObservedAt,
                long lastObservedAt) {
            this.tagId = tagId;
            this.observationCount = observationCount;
            this.strongestRssi = strongestRssi;
            this.firstObservedAt = firstObservedAt;
            this.lastObservedAt = lastObservedAt;
        }

        /** Returns the observed TagId. */
        public TagId tagId() {
            return tagId;
        }

        /** Returns the number of observations collected for this TagId. */
        public long observationCount() {
            return observationCount;
        }

        /** Returns the strongest RSSI observed for this TagId. */
        public int strongestRssi() {
            return strongestRssi;
        }

        /** Returns the first observation timestamp for this TagId. */
        public TimingTimestamp firstObservedAt() {
            return CompactTimingTime.toTimestamp(firstObservedAt);
        }

        /** Returns the most recent observation timestamp for this TagId. */
        public TimingTimestamp lastObservedAt() {
            return CompactTimingTime.toTimestamp(lastObservedAt);
        }
    }

    private final RegistrationId registrationId;
    private final List<TagStats> tags;
    private final TagId selectedTagId;
    private final int selectedRssi;
    private final long selectedObservedAt;

    /** Creates one immutable snapshot of an open passage burst. */
    TagPassageSnapshot(
            RegistrationId registrationId,
            List<TagStats> tags,
            TagId selectedTagId,
            int selectedRssi,
            long selectedObservedAt) {
        this.registrationId = registrationId;
        this.tags =
                Collections.unmodifiableList(
                        new ArrayList<TagStats>(tags));
        this.selectedTagId = selectedTagId;
        this.selectedRssi = selectedRssi;
        this.selectedObservedAt = selectedObservedAt;
    }

    /** Returns the RegistrationId whose passage is being aggregated. */
    public RegistrationId registrationId() {
        return registrationId;
    }

    /** Returns immutable per-tag diagnostic statistics for the passage. */
    public List<TagStats> tags() {
        return tags;
    }

    /** Returns the TagId currently selected by maximum RSSI. */
    public TagId selectedTagId() {
        return selectedTagId;
    }

    /** Returns the RSSI of the currently selected observation. */
    public int selectedRssi() {
        return selectedRssi;
    }

    /** Returns the timestamp of the currently selected observation. */
    public TimingTimestamp selectedObservedAt() {
        return CompactTimingTime.toTimestamp(selectedObservedAt);
    }
}
