package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

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
        RegistrationId id = new RegistrationId("N0001");

        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());

        filter.recordRegistration(id);

        assertTrue(filter.hasPendingState());
        assertTrue(filter.isDuplicate(id));
    }

    @Test
    public void registrationsWithoutAcceptedRecordRemainEligible() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);

        assertFalse(filter.isDuplicate(new RegistrationId("N0002")));
        assertFalse(filter.isDuplicate(new RegistrationId("N0003")));
        assertFalse(filter.hasPendingState());
    }

    @Test
    public void expiredAcceptedRegistrationBecomesEligibleImmediately() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N0001");

        filter.recordRegistration(id);
        clock.advanceNanos(100L);

        assertFalse(filter.isDuplicate(id));
        assertFalse(filter.hasPendingState());
    }

    @Test
    public void periodicCleanupRemovesExpiredState() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N0001");

        filter.recordRegistration(id);
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
        RegistrationId id = new RegistrationId("N0001");

        filter.recordRegistration(id);

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
