package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ScheduledTaskRunnerTest {

    @Test
    public void delayedContinuationReleasesPhysicalWorker()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialScheduledExecutor firstLane =
                new SerialScheduledExecutor(
                        8,
                        "first",
                        worker);
        SerialScheduledExecutor secondLane =
                new SerialScheduledExecutor(
                        8,
                        "second",
                        worker);
        ScheduledTaskRunner runner =
                new ScheduledTaskRunner(
                        firstLane,
                        Duration.ofSeconds(1));

        runner.start();
        secondLane.start();

        try {
            CountDownLatch beginDone =
                    new CountDownLatch(1);
            CountDownLatch completeDone =
                    new CountDownLatch(1);

            CompletableFuture<Void> delayed =
                    runner.runDelayed(
                            () -> {
                                beginDone.countDown();
                                return Duration.ofMillis(200);
                            },
                            completeDone::countDown);

            assertTrue(
                    beginDone.await(
                            500,
                            TimeUnit.MILLISECONDS));

            CountDownLatch siblingRan =
                    new CountDownLatch(1);
            assertTrue(
                    secondLane.execute(
                            siblingRan::countDown));
            assertTrue(
                    "elapsed delay must not occupy the shared worker",
                    siblingRan.await(
                            75,
                            TimeUnit.MILLISECONDS));

            runner.await(delayed);
            assertEquals(
                    0L,
                    completeDone.getCount());
        } finally {
            runner.close();
            secondLane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void timeoutInterruptsRunningTask()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialScheduledExecutor lane =
                new SerialScheduledExecutor(
                        4,
                        "timeout",
                        worker);
        ScheduledTaskRunner runner =
                new ScheduledTaskRunner(
                        lane,
                        Duration.ofMillis(50));
        runner.start();

        CountDownLatch interrupted =
                new CountDownLatch(1);

        try {
            CompletableFuture<Void> task =
                    runner.runAsync(
                            () -> {
                                try {
                                    Thread.sleep(
                                            TimeUnit.SECONDS.toMillis(10));
                                } catch (InterruptedException ex) {
                                    interrupted.countDown();
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(
                                            "interrupted",
                                            ex);
                                }
                            });

            try {
                runner.await(task);
                fail("expected task timeout");
            } catch (ScheduledTaskRunner.OperationException expected) {
                assertEquals(
                        ScheduledTaskRunner.FailureReason.TIMEOUT,
                        expected.reason());
            }

            assertTrue(
                    "timed-out task must be interrupted",
                    interrupted.await(
                            500,
                            TimeUnit.MILLISECONDS));
        } finally {
            runner.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void rejectedBackingWorkerIsOverloaded() {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        worker.shutdownNow();

        SerialScheduledExecutor lane =
                new SerialScheduledExecutor(
                        4,
                        "rejected",
                        worker);
        ScheduledTaskRunner runner =
                new ScheduledTaskRunner(
                        lane,
                        Duration.ofMillis(100));

        runner.start();
        try {
            CompletableFuture<Void> task =
                    runner.runAsync(
                            () -> {
                            });

            try {
                runner.await(task);
                fail("expected rejection");
            } catch (ScheduledTaskRunner.OperationException expected) {
                assertEquals(
                        ScheduledTaskRunner.FailureReason.OVERLOADED,
                        expected.reason());
            }
        } finally {
            runner.close();
        }
    }
}
