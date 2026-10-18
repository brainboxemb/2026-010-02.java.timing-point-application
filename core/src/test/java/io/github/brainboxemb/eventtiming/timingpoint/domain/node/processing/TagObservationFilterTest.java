package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagObservationFilterTest {
    private static final RegistrationId REGISTRATION_ID =
            new RegistrationId("N0001");
    private static final TimingTimestamp OBSERVED_1 =
            TimingTimestamp.parse("2026-10-01T12:00:00.00Z");
    private static final TimingTimestamp OBSERVED_2 =
            TimingTimestamp.parse("2026-10-01T12:00:00.10Z");
    private static final TimingTimestamp OBSERVED_3 =
            TimingTimestamp.parse("2026-10-01T12:00:00.20Z");

    @Test
    public void selectsStrongestRssiAcrossDifferentTagsForOneRegistration() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<SelectedObservation>();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                metrics,
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));

        assertEquals(8, metrics.snapshot().passagePoolInitialSize());
        assertEquals(8, metrics.snapshot().passagePoolSize());

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1));
        filter.add(REGISTRATION_ID, observation("N0001B", -40, OBSERVED_2));
        filter.add(REGISTRATION_ID, observation("N0001A", -40, OBSERVED_3));

        assertTrue(filter.hasPendingState());
        assertEquals(1, metrics.snapshot().passagePoolInUse());
        assertEquals(1, metrics.snapshot().passagePoolPeakInUse());

        clock.advanceMillis(250L);
        filter.periodic();

        assertEquals(1, valid.size());
        assertEquals(REGISTRATION_ID, valid.get(0).registrationId);
        assertEquals(OBSERVED_2, valid.get(0).observedAt);
        assertFalse(filter.hasPendingState());
        assertEquals(0, metrics.snapshot().passagePoolInUse());
    }

    @Test
    public void processingDelayDoesNotSplitObservedPassage() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<SelectedObservation>();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                new TagProcessingMetrics(),
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1));
        clock.advanceMillis(5000L);
        filter.add(REGISTRATION_ID, observation("N0001B", -40, OBSERVED_2));

        assertTrue(valid.isEmpty());
        assertTrue(filter.hasPendingState());
    }

    @Test
    public void observationGapStartsNewPassageWithoutProcessingDelay() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<SelectedObservation>();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                new TagProcessingMetrics(),
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1));
        filter.add(
                REGISTRATION_ID,
                observation(
                        "N0001B",
                        -40,
                        TimingTimestamp.parse("2026-10-01T12:00:00.30Z")));

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_1, valid.get(0).observedAt);
    }

    @Test
    public void maximumObservedDurationStartsNewPassage() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<SelectedObservation>();
        TagObservationFilter filter = new TagObservationFilter(
                new TagProcessingPolicy(
                        Duration.ofMillis(250L),
                        Duration.ofMillis(150L),
                        Duration.ZERO,
                        Duration.ofMillis(10L),
                        8),
                clock,
                new TagProcessingMetrics(),
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1));
        filter.add(REGISTRATION_ID, observation("N0001B", -40, OBSERVED_2));
        filter.add(
                REGISTRATION_ID,
                observation(
                        "N0001A",
                        -30,
                        TimingTimestamp.parse("2026-10-01T12:00:00.16Z")));

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_2, valid.get(0).observedAt);
    }

    @Test
    public void discardReturnsPassageToPool() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                metrics,
                (registrationId, observedAt) -> {
                    throw new AssertionError("discarded passage must not close");
                });

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1));

        assertEquals(1, metrics.snapshot().passagePoolInUse());
        assertTrue(filter.discard(REGISTRATION_ID));
        assertFalse(filter.hasPendingState());
        assertEquals(0, metrics.snapshot().passagePoolInUse());
        assertFalse(filter.discard(REGISTRATION_ID));
    }

    private static TagProcessingPolicy policy() {
        return new TagProcessingPolicy(
                Duration.ofMillis(250L),
                Duration.ofMillis(1000L),
                Duration.ofMillis(1000L),
                Duration.ofMillis(50L),
                8);
    }

    private static TagObservation observation(
            String tagId,
            int rssi,
            TimingTimestamp observedAt) {
        return new TagObservation(
                new TagId(tagId),
                rssi,
                observedAt);
    }

    private static final class SelectedObservation {
        private final RegistrationId registrationId;
        private final TimingTimestamp observedAt;

        private SelectedObservation(
                RegistrationId registrationId,
                TimingTimestamp observedAt) {
            this.registrationId = registrationId;
            this.observedAt = observedAt;
        }
    }

    private static final class FakeMonotonicClock implements MonotonicClock {
        private long now;

        @Override
        public long nowNanos() {
            return now;
        }

        private void advanceMillis(long millis) {
            now += TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }
}
