package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.scheduling;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LaneSchedulerTest {

    @Test
    public void delayedOperationRunsOnOwningSerialLane()
            throws Exception {
        ScheduledThreadPoolExecutor worker =
                worker(
                        "lane-scheduler-delay");
        SerialLane lane =
                new SerialLane(
                        8,
                        "delay",
                        worker);
        LaneScheduler scheduler =
                new LaneScheduler(
                        lane,
                        worker);
        AtomicReference<String> threadName =
                new AtomicReference<String>();
        CountDownLatch done =
                new CountDownLatch(1);

        lane.start();
        try {
            scheduler.submitAfter(
                    () -> {
                        threadName.set(
                                Thread.currentThread()
                                        .getName());
                        done.countDown();
                        return null;
                    },
                    TimeUnit.MILLISECONDS.toNanos(
                            5L));

            assertTrue(
                    done.await(
                            1L,
                            TimeUnit.SECONDS));
            assertEquals(
                    "lane-scheduler-delay",
                    threadName.get());
        } finally {
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void fixedDelayContinuesAfterRuntimeFailure()
            throws Exception {
        ScheduledThreadPoolExecutor worker =
                worker(
                        "lane-scheduler-periodic");
        SerialLane lane =
                new SerialLane(
                        8,
                        "periodic",
                        worker);
        LaneScheduler scheduler =
                new LaneScheduler(
                        lane,
                        worker);
        AtomicInteger calls =
                new AtomicInteger();
        CountDownLatch secondCall =
                new CountDownLatch(1);

        lane.start();
        LaneScheduler.ScheduledRegistration registration =
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            if (calls.incrementAndGet()
                                    == 1) {
                                throw new IllegalStateException(
                                        "expected");
                            }
                            secondCall.countDown();
                        },
                        TimeUnit.MILLISECONDS.toNanos(
                                5L));
        try {
            assertTrue(
                    secondCall.await(
                            1L,
                            TimeUnit.SECONDS));
            assertTrue(
                    calls.get() >= 2);
        } finally {
            registration.close();
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void closingFixedDelayRegistrationStopsFutureRuns()
            throws Exception {
        ScheduledThreadPoolExecutor worker =
                worker(
                        "lane-scheduler-cancel");
        SerialLane lane =
                new SerialLane(
                        8,
                        "cancel",
                        worker);
        LaneScheduler scheduler =
                new LaneScheduler(
                        lane,
                        worker);
        AtomicInteger calls =
                new AtomicInteger();
        CountDownLatch firstCall =
                new CountDownLatch(1);

        lane.start();
        LaneScheduler.ScheduledRegistration registration =
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            calls.incrementAndGet();
                            firstCall.countDown();
                        },
                        TimeUnit.MILLISECONDS.toNanos(
                                50L));
        try {
            assertTrue(
                    firstCall.await(
                            1L,
                            TimeUnit.SECONDS));
            registration.close();

            int afterClose =
                    calls.get();
            Thread.sleep(
                    120L);
            assertEquals(
                    afterClose,
                    calls.get());
        } finally {
            registration.close();
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void cancellingDelayedOperationPreventsExecution()
            throws Exception {
        ScheduledThreadPoolExecutor worker =
                worker(
                        "lane-scheduler-one-shot-cancel");
        SerialLane lane =
                new SerialLane(
                        8,
                        "one-shot-cancel",
                        worker);
        LaneScheduler scheduler =
                new LaneScheduler(
                        lane,
                        worker);
        AtomicInteger calls =
                new AtomicInteger();

        lane.start();
        try {
            LaneScheduler.ScheduledOperation<Void> operation =
                    scheduler.submitAfter(
                            () -> {
                                calls.incrementAndGet();
                                return null;
                            },
                            TimeUnit.MILLISECONDS.toNanos(
                                    50L));
            operation.close();

            Thread.sleep(
                    120L);
            assertEquals(
                    0,
                    calls.get());
        } finally {
            lane.close();
            worker.shutdownNow();
        }
    }

    private static ScheduledThreadPoolExecutor worker(
            String threadName) {
        ScheduledThreadPoolExecutor worker =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable ->
                                new Thread(
                                        runnable,
                                        threadName));
        worker.setRemoveOnCancelPolicy(
                true);
        return worker;
    }
}
