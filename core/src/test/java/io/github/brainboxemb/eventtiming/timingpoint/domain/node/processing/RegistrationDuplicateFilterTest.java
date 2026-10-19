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

        assertEquals(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY,
                metrics.snapshot().duplicateStateInitialCapacity());
        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());

        filter.recordRegistration(id);

        assertTrue(filter.hasPendingState());
        assertTrue(filter.isDuplicate(id));
        assertEquals(1, metrics.snapshot().duplicateStateSize());
        assertEquals(1, metrics.snapshot().duplicateStatePeakSize());
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
    public void periodicCleanupKeepsOnlyRegistrationsInsideDuplicateWindow() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(8), clock, metrics);

        filter.recordRegistration(new RegistrationId("N0001"));
        clock.advanceNanos(50L);
        filter.recordRegistration(new RegistrationId("N0002"));
        clock.advanceNanos(50L);

        filter.periodic();

        assertFalse(filter.isDuplicate(new RegistrationId("N0001")));
        assertTrue(filter.isDuplicate(new RegistrationId("N0002")));
        assertEquals(1, metrics.snapshot().duplicateStateSize());
    }

    @Test
    public void duplicateStateCapacityIsIndependentFromObservationQueueCapacity() {
        TagProcessingMetrics smallQueueMetrics = new TagProcessingMetrics();
        TagProcessingMetrics largeQueueMetrics = new TagProcessingMetrics();

        new RegistrationDuplicateFilter(
                policy(2),
                new FakeMonotonicClock(),
                smallQueueMetrics);
        new RegistrationDuplicateFilter(
                policy(4096),
                new FakeMonotonicClock(),
                largeQueueMetrics);

        assertEquals(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY,
                smallQueueMetrics.snapshot().duplicateStateCapacity());
        assertEquals(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY,
                largeQueueMetrics.snapshot().duplicateStateCapacity());
    }

    @Test
    public void primitiveStateGrowsOnlyWhenActiveRegistrationsExceedCurrentStorage() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        TagProcessingMetrics metrics = new TagProcessingMetrics();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(8), clock, metrics);

        for (int number = 1;
                number <= ActiveDuplicateRegistrations.INITIAL_CAPACITY;
                number++) {
            filter.recordRegistration(new RegistrationId('N', number));
        }

        assertEquals(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY,
                metrics.snapshot().duplicateStateCapacity());

        filter.recordRegistration(
                new RegistrationId(
                        'N',
                        ActiveDuplicateRegistrations.INITIAL_CAPACITY + 1));

        assertEquals(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY * 2,
                metrics.snapshot().duplicateStateCapacity());
        assertEquals(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY + 1,
                metrics.snapshot().duplicateStateSize());
        assertEquals(
                ActiveDuplicateRegistrations.INITIAL_CAPACITY + 1,
                metrics.snapshot().duplicateStatePeakSize());
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
        assertEquals(0, metrics.snapshot().duplicateStateSize());
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
