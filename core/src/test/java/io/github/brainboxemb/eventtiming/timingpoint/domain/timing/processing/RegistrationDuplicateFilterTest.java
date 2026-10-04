package io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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

        assertEquals(
                RegistrationDuplicateFilter.Result.DUPLICATE,
                filter.submitIfNew(id, () -> {
                    submissions.incrementAndGet();
                    return CommandAdmission.ACCEPTED;
                }));
        assertEquals(1, submissions.get());

        clock.advanceNanos(100L);

        assertEquals(
                RegistrationDuplicateFilter.Result.ACCEPTED,
                filter.submitIfNew(id, () -> {
                    submissions.incrementAndGet();
                    return CommandAdmission.ACCEPTED;
                }));
        assertEquals(2, submissions.get());
    }

    @Test
    public void fullAndNotRunningDoNotStartDuplicateWindow() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId fullId = new RegistrationId("N-FULL");
        RegistrationId stoppedId = new RegistrationId("N-STOPPED");

        assertEquals(
                RegistrationDuplicateFilter.Result.FULL,
                filter.submitIfNew(
                        fullId,
                        () -> CommandAdmission.FULL));
        assertEquals(
                RegistrationDuplicateFilter.Result.ACCEPTED,
                filter.submitIfNew(
                        fullId,
                        () -> CommandAdmission.ACCEPTED));

        assertEquals(
                RegistrationDuplicateFilter.Result.NOT_RUNNING,
                filter.submitIfNew(
                        stoppedId,
                        () -> CommandAdmission.NOT_RUNNING));
        assertEquals(
                RegistrationDuplicateFilter.Result.ACCEPTED,
                filter.submitIfNew(
                        stoppedId,
                        () -> CommandAdmission.ACCEPTED));
    }

    @Test
    public void concurrentChecksForSameRegistrationSubmitOnlyOnce() throws Exception {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        RegistrationDuplicateFilter filter =
                new RegistrationDuplicateFilter(policy(), clock);
        RegistrationId id = new RegistrationId("N-001");
        AtomicInteger submissions = new AtomicInteger();
        List<RegistrationDuplicateFilter.Result> results =
                Collections.synchronizedList(
                        new ArrayList<RegistrationDuplicateFilter.Result>());
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = callers.submit(() -> {
                await(start);
                results.add(filter.submitIfNew(id, () -> {
                    submissions.incrementAndGet();
                    return CommandAdmission.ACCEPTED;
                }));
            });
            Future<?> second = callers.submit(() -> {
                await(start);
                results.add(filter.submitIfNew(id, () -> {
                    submissions.incrementAndGet();
                    return CommandAdmission.ACCEPTED;
                }));
            });

            start.countDown();
            first.get();
            second.get();

            assertEquals(1, submissions.get());
            assertEquals(1, count(
                    results,
                    RegistrationDuplicateFilter.Result.ACCEPTED));
            assertEquals(1, count(
                    results,
                    RegistrationDuplicateFilter.Result.DUPLICATE));
        } finally {
            callers.shutdownNow();
        }
    }

    private static TagProcessingPolicy policy() {
        return new TagProcessingPolicy(
                Duration.ofNanos(10L),
                Duration.ofNanos(20L),
                Duration.ofNanos(100L),
                Duration.ofNanos(5L));
    }

    private static int count(
            List<RegistrationDuplicateFilter.Result> results,
            RegistrationDuplicateFilter.Result wanted) {
        int count = 0;
        for (RegistrationDuplicateFilter.Result result : results) {
            if (result == wanted) {
                count++;
            }
        }
        return count;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting test start", ex);
        }
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
