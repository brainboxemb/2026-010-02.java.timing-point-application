package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TagObservationFilterTest {
    private static final RegistrationId REGISTRATION_ID = new RegistrationId("N0001");
    private static final long OBSERVED_1 = Instant.parse("2026-10-01T12:00:00.000Z").toEpochMilli();
    private static final long OBSERVED_2 = Instant.parse("2026-10-01T12:00:00.100Z").toEpochMilli();
    private static final long OBSERVED_3 = Instant.parse("2026-10-01T12:00:00.200Z").toEpochMilli();

    @Test
    public void selectsStrongestRssiAcrossDifferentTagsForOneRegistration() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<SelectedObservation>();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                metrics,
                (registrationId, observedAtMillis) ->
                        valid.add(new SelectedObservation(registrationId, observedAtMillis)));

        assertEquals(8, metrics.snapshot().passagePoolInitialSize());
        assertEquals(8, metrics.snapshot().passagePoolSize());

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1, 0L));
        filter.add(REGISTRATION_ID, observation("N0001B", -40, OBSERVED_2, 100L));
        filter.add(REGISTRATION_ID, observation("N0001A", -40, OBSERVED_3, 200L));

        assertTrue(filter.hasPendingState());
        assertEquals(1, metrics.snapshot().passagePoolInUse());
        assertEquals(1, metrics.snapshot().passagePoolPeakInUse());

        clock.setMillis(450L);
        filter.periodic();

        assertEquals(1, valid.size());
        assertEquals(REGISTRATION_ID, valid.get(0).registrationId);
        assertEquals(OBSERVED_2, valid.get(0).observedAtMillis);
        assertFalse(filter.hasPendingState());
        assertEquals(0, metrics.snapshot().passagePoolInUse());
    }

    @Test
    public void processingDelayDoesNotSplitAlreadyObservedPassage() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<SelectedObservation>();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                new TagProcessingMetrics(),
                (registrationId, observedAtMillis) ->
                        valid.add(new SelectedObservation(registrationId, observedAtMillis)));

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1, 0L));

        /*
         * Five seconds pass locally before the already-captured second read is processed.
         * Its antenna monotonic time is still only 100 ms later.
         */
        clock.setMillis(5000L);
        filter.add(REGISTRATION_ID, observation("N0001B", -40, OBSERVED_2, 100L));

        assertTrue(valid.isEmpty());
        assertTrue(filter.hasPendingState());
    }

    @Test
    public void quietTimeoutBoundaryStartsNewPassage() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<SelectedObservation>();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                new TagProcessingMetrics(),
                (registrationId, observedAtMillis) ->
                        valid.add(new SelectedObservation(registrationId, observedAtMillis)));

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1, 0L));
        filter.add(
                REGISTRATION_ID,
                observation(
                        "N0001B",
                        -40,
                        Instant.parse("2026-10-01T12:00:00.250Z").toEpochMilli(),
                        250L));

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_1, valid.get(0).observedAtMillis);
    }

    @Test
    public void maximumPassageDurationBoundaryStartsNewPassage() {
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
                (registrationId, observedAtMillis) ->
                        valid.add(new SelectedObservation(registrationId, observedAtMillis)));

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1, 0L));
        filter.add(REGISTRATION_ID, observation("N0001B", -40, OBSERVED_2, 100L));
        filter.add(
                REGISTRATION_ID,
                observation(
                        "N0001A",
                        -30,
                        Instant.parse("2026-10-01T12:00:00.150Z").toEpochMilli(),
                        150L));

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_2, valid.get(0).observedAtMillis);
    }

    @Test
    public void discardReturnsPassageToPool() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        TagObservationFilter filter = new TagObservationFilter(
                policy(),
                clock,
                metrics,
                (registrationId, observedAtMillis) -> {
                    throw new AssertionError("discarded passage must not close");
                });

        filter.add(REGISTRATION_ID, observation("N0001A", -60, OBSERVED_1, 0L));

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
            long observedAtMillis,
            long monotonicMillis) {

        return new TagObservation(
                new TagId(tagId),
                rssi,
                observedAtMillis,
                TimeUnit.MILLISECONDS.toNanos(monotonicMillis));
    }

    private static final class SelectedObservation {
        private final RegistrationId registrationId;
        private final long observedAtMillis;

        private SelectedObservation(RegistrationId registrationId, long observedAtMillis) {
            this.registrationId = registrationId;
            this.observedAtMillis = observedAtMillis;
        }
    }

    private static final class FakeMonotonicClock implements MonotonicClock {
        private long now;

        @Override
        public long nowNanos() {
            return now;
        }

        private void setMillis(long millis) {
            now = TimeUnit.MILLISECONDS.toNanos(millis);
        }
    }
}
