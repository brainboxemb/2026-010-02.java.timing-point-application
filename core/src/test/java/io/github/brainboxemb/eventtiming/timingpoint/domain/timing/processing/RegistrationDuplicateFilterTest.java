package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RegistrationDuplicateFilterTest {

    @Test
    public void acceptedSubmissionStartsDuplicateWindow() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N-001");
        AtomicInteger submissions = new AtomicInteger();

        assertEquals(
                RegistrationDuplicateFilter.Result.ACCEPTED,
                filter.submitIfNew(id, () -> {
                    submissions.incrementAndGet();
                    return CommandAdmission.ACCEPTED;
                }));
        assertTrue(filter.hasPendingState());

        assertEquals(
                RegistrationDuplicateFilter.Result.DUPLICATE,
                filter.submitIfNew(id, () -> {
                    submissions.incrementAndGet();
                    return CommandAdmission.ACCEPTED;
                }));
        assertEquals(1, submissions.get());
    }

    @Test
    public void fullAndNotRunningDoNotStartDuplicateWindow() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);

        assertEquals(
                RegistrationDuplicateFilter.Result.FULL,
                filter.submitIfNew(
                        new RegistrationId("N-FULL"),
                        () -> CommandAdmission.FULL));
        assertEquals(
                RegistrationDuplicateFilter.Result.NOT_RUNNING,
                filter.submitIfNew(
                        new RegistrationId("N-STOPPED"),
                        () -> CommandAdmission.NOT_RUNNING));
        assertFalse(filter.hasPendingState());
    }

    @Test
    public void periodicCleanupRemovesExpiredState() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N-001");

        filter.submitIfNew(id, () -> CommandAdmission.ACCEPTED);
        filter.periodic();
        clock.advanceNanos(100L);
        filter.periodic();

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
