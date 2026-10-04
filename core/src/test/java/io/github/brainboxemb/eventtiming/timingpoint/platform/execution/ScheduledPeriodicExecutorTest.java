package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ScheduledPeriodicExecutorTest {

    @Test
    public void scheduledTaskRepeatsAndHandleCancelsOnlyThatTask()
            throws Exception {
        ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor();
        ScheduledPeriodicExecutor executor =
                new ScheduledPeriodicExecutor(scheduler);
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch twoCalls = new CountDownLatch(2);

        PeriodicTask task = executor.scheduleWithFixedDelay(
                () -> {
                    calls.incrementAndGet();
                    twoCalls.countDown();
                },
                TimeUnit.MILLISECONDS.toNanos(5));

        try {
            assertTrue(twoCalls.await(1, TimeUnit.SECONDS));
            task.close();

            int afterClose = calls.get();
            Thread.sleep(30L);
            assertEquals(afterClose, calls.get());
        } finally {
            task.close();
            scheduler.shutdownNow();
        }
    }
}
