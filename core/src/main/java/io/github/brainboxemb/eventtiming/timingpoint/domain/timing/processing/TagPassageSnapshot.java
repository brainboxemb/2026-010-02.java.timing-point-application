package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.eventdata.TagId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable engineering view of one currently aggregated registration passage.
 *
 * <p>This is diagnostic state, not committed TimingData.</p>
 */
public final class TagPassageSnapshot {

    public static final class TagStats {
        private final TagId tagId;
        private final long observationCount;
        private final int strongestRssi;
        private final TimingTimestamp firstObservedAt;
        private final TimingTimestamp lastObservedAt;

        TagStats(
                TagId tagId,
                long observationCount,
                int strongestRssi,
                TimingTimestamp firstObservedAt,
                TimingTimestamp lastObservedAt) {
            this.tagId = tagId;
            this.observationCount = observationCount;
            this.strongestRssi = strongestRssi;
            this.firstObservedAt = firstObservedAt;
            this.lastObservedAt = lastObservedAt;
        }

        public TagId tagId() {
            return tagId;
        }

        public long observationCount() {
            return observationCount;
        }

        public int strongestRssi() {
            return strongestRssi;
        }

        public TimingTimestamp firstObservedAt() {
            return firstObservedAt;
        }

        public TimingTimestamp lastObservedAt() {
            return lastObservedAt;
        }
    }

    private final RegistrationId registrationId;
    private final List<TagStats> tags;
    private final TagId selectedTagId;
    private final int selectedRssi;
    private final TimingTimestamp selectedObservedAt;

    TagPassageSnapshot(
            RegistrationId registrationId,
            List<TagStats> tags,
            TagId selectedTagId,
            int selectedRssi,
            TimingTimestamp selectedObservedAt) {
        this.registrationId = registrationId;
        this.tags =
                Collections.unmodifiableList(
                        new ArrayList<TagStats>(tags));
        this.selectedTagId = selectedTagId;
        this.selectedRssi = selectedRssi;
        this.selectedObservedAt = selectedObservedAt;
    }

    public RegistrationId registrationId() {
        return registrationId;
    }

    public List<TagStats> tags() {
        return tags;
    }

    public TagId selectedTagId() {
        return selectedTagId;
    }

    public int selectedRssi() {
        return selectedRssi;
    }

    public TimingTimestamp selectedObservedAt() {
        return selectedObservedAt;
    }
}
