package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.DecryptedTagId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TagObservationFilterTest {
    private static final RegistrationId REGISTRATION_ID =
            new RegistrationId("N-001");
    private static final TimingTimestamp OBSERVED_1 =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp OBSERVED_2 =
            TimingTimestamp.parse("2026-10-01T12:00:00.100000000Z");
    private static final TimingTimestamp OBSERVED_3 =
            TimingTimestamp.parse("2026-10-01T12:00:00.200000000Z");

    @Test
    public void selectsStrongestRssiAndKeepsEarlierObservationOnEqualMaximum() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingCounters counters = new TagProcessingCounters();
        List<SelectedObservation> valid = new ArrayList<>();
        TagObservationFilter filter = new TagObservationFilter(
                manualPolicy(),
                clock,
                counters,
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));

        filter.add(
                REGISTRATION_ID,
                observation("TAG-A", -60, OBSERVED_1));
        clock.advanceNanos(10L);
        filter.add(
                REGISTRATION_ID,
                observation("TAG-B", -40, OBSERVED_2));
        clock.advanceNanos(10L);
        filter.add(
                REGISTRATION_ID,
                observation("TAG-A", -40, OBSERVED_3));

        clock.advanceNanos(100L);
        filter.periodic();

        assertEquals(1, valid.size());
        assertEquals(REGISTRATION_ID, valid.get(0).registrationId);
        assertEquals(OBSERVED_2, valid.get(0).observedAt);
        assertEquals(1L, counters.snapshot().closedBursts());
    }

    @Test
    public void maximumDurationClosesContinuouslyVisibleRegistration() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<>();
        TagObservationFilter filter = new TagObservationFilter(
                new TagProcessingPolicy(
                        Duration.ofNanos(100L),
                        Duration.ofNanos(150L),
                        Duration.ZERO,
                        Duration.ofDays(1)),
                clock,
                new TagProcessingCounters(),
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));

        filter.add(
                REGISTRATION_ID,
                observation("TAG-A", -60, OBSERVED_1));
        clock.advanceNanos(90L);
        filter.add(
                REGISTRATION_ID,
                observation("TAG-B", -40, OBSERVED_2));
        clock.advanceNanos(60L);

        filter.periodic();

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_2, valid.get(0).observedAt);
    }

    @Test
    public void concurrentAddsUpdateOneRegistrationBurstSafely()
            throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        List<SelectedObservation> valid =
                Collections.synchronizedList(
                        new ArrayList<SelectedObservation>());
        TagObservationFilter filter = new TagObservationFilter(
                manualPolicy(),
                clock,
                new TagProcessingCounters(),
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = callers.submit(() -> {
                await(start);
                filter.add(
                        REGISTRATION_ID,
                        observation("TAG-A", -60, OBSERVED_1));
            });
            Future<?> second = callers.submit(() -> {
                await(start);
                filter.add(
                        REGISTRATION_ID,
                        observation("TAG-B", -30, OBSERVED_2));
            });

            start.countDown();
            first.get();
            second.get();

            clock.advanceNanos(100L);
            filter.periodic();

            assertEquals(1, valid.size());
            assertEquals(OBSERVED_2, valid.get(0).observedAt);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    public void quietTimeoutClosesOnExpiryPass() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<SelectedObservation> valid = new ArrayList<>();
        TagObservationFilter filter = new TagObservationFilter(
                manualPolicy(),
                clock,
                new TagProcessingCounters(),
                (registrationId, observedAt) -> valid.add(
                        new SelectedObservation(registrationId, observedAt)));

        filter.add(
                REGISTRATION_ID,
                observation("TAG-A", -50, OBSERVED_1));
        clock.advanceNanos(100L);

        filter.periodic();

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_1, valid.get(0).observedAt);
    }

    private static TagProcessingPolicy manualPolicy() {
        return new TagProcessingPolicy(
                Duration.ofNanos(100L),
                Duration.ofNanos(500L),
                Duration.ofNanos(1000L),
                Duration.ofDays(1));
    }

    private static TagObservation observation(
            String tagId,
            int rssi,
            TimingTimestamp observedAt) {
        return new TagObservation(
                new DecryptedTagId(tagId),
                rssi,
                observedAt);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while awaiting test start",
                    ex);
        }
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
        private volatile long now;

        @Override
        public long nowNanos() {
            return now;
        }

        private void advanceNanos(long nanos) {
            now += nanos;
        }
    }
}
