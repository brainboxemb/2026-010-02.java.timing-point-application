package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Duration;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegistrationDuplicateFilterTest {

    @Test
    public void registrationIsSuppressedOnlyAfterAcceptedStateIsRecorded() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N-001");

        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());

        filter.recordAccepted(id);

        assertTrue(filter.hasPendingState());
        assertTrue(filter.isDuplicate(id));
    }

    @Test
    public void registrationsWithoutAcceptedRecordRemainEligible() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);

        assertFalse(filter.isDuplicate(new RegistrationId("N-FULL")));
        assertFalse(filter.isDuplicate(new RegistrationId("N-STOPPED")));
        assertFalse(filter.hasPendingState());
    }

    @Test
    public void expiredAcceptedRegistrationBecomesEligibleImmediately() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N-001");

        filter.recordAccepted(id);
        clock.advanceNanos(100L);

        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());
    }

    @Test
    public void periodicCleanupRemovesExpiredState() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N-001");

        filter.recordAccepted(id);
        filter.periodic();
        clock.advanceNanos(100L);
        filter.periodic();

        assertFalse(filter.hasPendingState());
    }

    @Test
    public void zeroDuplicateWindowNeverCreatesSuppressionState() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(
                        new TagProcessingPolicy(
                                Duration.ofNanos(10L),
                                Duration.ofNanos(20L),
                                Duration.ZERO,
                                Duration.ofNanos(5L),
                                8),
                        clock);
        RegistrationId id = new RegistrationId("N-001");

        filter.recordAccepted(id);

        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());
    }

    private static TagProcessingPolicy policy() {
        return new TagProcessingPolicy(
                Duration.ofNanos(10L),
                Duration.ofNanos(20L),
                Duration.ofNanos(100L),
                Duration.ofNanos(5L),
                8);
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
