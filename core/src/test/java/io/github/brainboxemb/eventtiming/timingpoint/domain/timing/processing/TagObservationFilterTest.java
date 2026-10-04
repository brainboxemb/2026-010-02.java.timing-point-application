package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagId;
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
        List<TagObservation> valid = new ArrayList<>();
        TagObservationFilter filter = new TagObservationFilter(
                manualPolicy(),
                clock,
                counters,
                valid::add);

        filter.add(observation("TAG-001", -60, OBSERVED_1));
        clock.advanceNanos(10L);
        filter.add(observation("TAG-001", -40, OBSERVED_2));
        clock.advanceNanos(10L);
        filter.add(observation("TAG-001", -40, OBSERVED_3));

        clock.advanceNanos(100L);
        filter.expire();

        assertEquals(1, valid.size());
        assertEquals(-40, valid.get(0).rssi());
        assertEquals(OBSERVED_2, valid.get(0).observedAt());

        TagProcessingCounters.Snapshot snapshot = counters.snapshot();
        assertEquals(3L, snapshot.observations());
        assertEquals(1L, snapshot.closedBursts());
    }

    @Test
    public void maximumDurationClosesContinuouslyVisibleTag() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<TagObservation> valid = new ArrayList<>();
        TagObservationFilter filter = new TagObservationFilter(
                new TagProcessingPolicy(
                        Duration.ofNanos(100L),
                        Duration.ofNanos(150L),
                        Duration.ZERO,
                        Duration.ofDays(1)),
                clock,
                new TagProcessingCounters(),
                valid::add);

        filter.add(observation("TAG-001", -60, OBSERVED_1));
        clock.advanceNanos(90L);
        filter.add(observation("TAG-001", -40, OBSERVED_2));
        clock.advanceNanos(60L);

        filter.expire();

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_2, valid.get(0).observedAt());
    }

    @Test
    public void concurrentAddsUpdateOneBurstSafely() throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        List<TagObservation> valid =
                Collections.synchronizedList(new ArrayList<TagObservation>());
        TagObservationFilter filter = new TagObservationFilter(
                manualPolicy(),
                clock,
                new TagProcessingCounters(),
                valid::add);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = callers.submit(() -> {
                await(start);
                filter.add(observation("TAG-001", -60, OBSERVED_1));
            });
            Future<?> second = callers.submit(() -> {
                await(start);
                filter.add(observation("TAG-001", -30, OBSERVED_2));
            });

            start.countDown();
            first.get();
            second.get();

            clock.advanceNanos(100L);
            filter.expire();

            assertEquals(1, valid.size());
            assertEquals(-30, valid.get(0).rssi());
            assertEquals(OBSERVED_2, valid.get(0).observedAt());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    public void quietTimeoutClosesOnExpiryPass() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        List<TagObservation> valid = new ArrayList<>();
        TagObservationFilter filter = new TagObservationFilter(
                manualPolicy(),
                clock,
                new TagProcessingCounters(),
                valid::add);

        filter.add(observation("TAG-001", -50, OBSERVED_1));
        clock.advanceNanos(100L);

        filter.expire();

        assertEquals(1, valid.size());
        assertEquals(OBSERVED_1, valid.get(0).observedAt());
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
        return new TagObservation(new TagId(tagId), rssi, observedAt);
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
