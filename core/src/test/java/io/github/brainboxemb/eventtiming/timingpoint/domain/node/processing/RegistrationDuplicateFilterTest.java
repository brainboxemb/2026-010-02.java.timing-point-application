package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Duration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegistrationDuplicateFilterTest {

    @Test
    public void registrationIsSuppressedOnlyAfterAcceptedStateIsRecorded() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(8), clock, metrics);
        RegistrationId id = new RegistrationId("N0001");

        assertEquals(16, metrics.snapshot().duplicateTableInitialCapacity());
        assertEquals(16, metrics.snapshot().duplicateTableCapacity());
        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());

        filter.recordRegistration(id);

        assertTrue(filter.hasPendingState());
        assertTrue(filter.isDuplicate(id));
        assertEquals(1, metrics.snapshot().duplicateTableSize());
        assertEquals(1, metrics.snapshot().duplicateTablePeakSize());
    }

    @Test
    public void registrationsWithoutAcceptedRecordRemainEligible() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(8), clock, new TagProcessingMetrics());

        assertFalse(filter.isDuplicate(new RegistrationId("N0002")));
        assertFalse(filter.isDuplicate(new RegistrationId("N0003")));
        assertFalse(filter.hasPendingState());
    }

    @Test
    public void exactDuplicateWindowBoundaryMakesRegistrationEligible() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(8), clock, new TagProcessingMetrics());
        RegistrationId id = new RegistrationId("N0001");

        filter.recordRegistration(id);
        clock.advanceNanos(99L);
        assertTrue(filter.isDuplicate(id));

        clock.advanceNanos(1L);
        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());
    }

    @Test
    public void periodicCleanupRemovesExpiredState() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(8), clock, new TagProcessingMetrics());
        RegistrationId id = new RegistrationId("N0001");

        filter.recordRegistration(id);
        filter.periodic();
        clock.advanceNanos(100L);
        filter.periodic();

        assertFalse(filter.hasPendingState());
    }

    @Test
    public void primitiveTableGrowsOnlyAfterInitialLoadThresholdIsExceeded() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(2), clock, metrics);

        assertEquals(4, metrics.snapshot().duplicateTableInitialCapacity());

        filter.recordRegistration(new RegistrationId("N0001"));
        filter.recordRegistration(new RegistrationId("N0002"));
        assertEquals(4, metrics.snapshot().duplicateTableCapacity());

        filter.recordRegistration(new RegistrationId("N0003"));

        assertEquals(8, metrics.snapshot().duplicateTableCapacity());
        assertEquals(3, metrics.snapshot().duplicateTableSize());
        assertEquals(3, metrics.snapshot().duplicateTablePeakSize());
    }

    @Test
    public void zeroDuplicateWindowNeverCreatesSuppressionState() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(
                        new TagProcessingPolicy(
                                Duration.ofNanos(10L),
                                Duration.ofNanos(20L),
                                Duration.ZERO,
                                Duration.ofNanos(5L),
                                8),
                        clock,
                        metrics);
        RegistrationId id = new RegistrationId("N0001");

        filter.recordRegistration(id);

        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());
        assertEquals(0, metrics.snapshot().duplicateTableSize());
    }

    private static TagProcessingPolicy policy(int queueCapacity) {
        return new TagProcessingPolicy(
                Duration.ofNanos(10L),
                Duration.ofNanos(20L),
                Duration.ofNanos(100L),
                Duration.ofNanos(5L),
                queueCapacity);
    }

    private static final class FakeMonotonicClock implements MonotonicClock {
        private long now;

        @Override
        public long nowNanos() {
            return now;
        }

        private void advanceNanos(long nanos) {
            now += nanos;
        }
    }
}
