package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.taskrunners;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialTaskRunnerSchedulingTest {

    @Test
    public void yieldingAgainYieldsToAlreadyQueuedWork()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialLane lane =
                new SerialLane(
                        8,
                        "yielding-yield",
                        worker);
        SerialTaskRunner runner =
                new SerialTaskRunner(
                        lane,
                        worker,
                        Duration.ofSeconds(1));
        runner.start();

        List<String> order =
                Collections.synchronizedList(
                        new ArrayList<String>());
        CountDownLatch firstStepEntered =
                new CountDownLatch(1);
        CountDownLatch allowFirstStepReturn =
                new CountDownLatch(1);
        AtomicInteger step =
                new AtomicInteger();

        try {
            CompletableFuture<Void> task =
                    runner.runTask(
                            () -> {
                                if (step.getAndIncrement() == 0) {
                                    order.add(
                                            "task-1");
                                    firstStepEntered.countDown();
                                    try {
                                        if (!allowFirstStepReturn.await(
                                                1,
                                                TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "test did not release first step");
                                        }
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread()
                                                .interrupt();
                                        throw new IllegalStateException(
                                                "test task interrupted",
                                                ex);
                                    }
                                    return TaskStep.again();
                                }

                                order.add(
                                        "task-2");
                                return TaskStep.done();
                            });

            assertTrue(
                    firstStepEntered.await(
                            500,
                            TimeUnit.MILLISECONDS));

            assertTrue(
                    runner.execute(
                            () -> order.add(
                                    "sibling")));

            allowFirstStepReturn.countDown();
            runner.await(
                    task);

            assertEquals(
                    3,
                    order.size());
            assertEquals(
                    "task-1",
                    order.get(0));
            assertEquals(
                    "sibling",
                    order.get(1));
            assertEquals(
                    "task-2",
                    order.get(2));
        } finally {
            runner.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void yieldingAfterReleasesPhysicalWorker()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialLane taskLane =
                new SerialLane(
                        8,
                        "yielding-delay",
                        worker);
        SerialLane siblingLane =
                new SerialLane(
                        8,
                        "yielding-sibling",
                        worker);
        SerialTaskRunner runner =
                new SerialTaskRunner(
                        taskLane,
                        worker,
                        Duration.ofSeconds(1));
        runner.start();
        siblingLane.start();

        AtomicInteger step =
                new AtomicInteger();
        CountDownLatch waiting =
                new CountDownLatch(1);

        try {
            CompletableFuture<Void> task =
                    runner.runTask(
                            () -> {
                                if (step.getAndIncrement() == 0) {
                                    waiting.countDown();
                                    return TaskStep.after(
                                            Duration.ofMillis(
                                                    200));
                                }
                                return TaskStep.done();
                            });

            assertTrue(
                    waiting.await(
                            500,
                            TimeUnit.MILLISECONDS));

            CountDownLatch siblingRan =
                    new CountDownLatch(1);
            assertEquals(
                    SerialLane.AdmissionResult.ACCEPTED,
                    siblingLane.offer(
                            siblingRan::countDown));
            assertTrue(
                    "yielding delay must release the shared worker",
                    siblingRan.await(
                            75,
                            TimeUnit.MILLISECONDS));

            runner.await(
                    task);
            assertEquals(
                    2,
                    step.get());
        } finally {
            runner.close();
            siblingLane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void timeoutInterruptsRunningTask()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialLane lane =
                new SerialLane(
                        4,
                        "timeout",
                        worker);
        SerialTaskRunner runner =
                new SerialTaskRunner(
                        lane,
                        worker,
                        Duration.ofMillis(50));
        runner.start();

        CountDownLatch interrupted =
                new CountDownLatch(1);

        try {
            CompletableFuture<Void> task =
                    runner.runTask(
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
                                return TaskStep.done();
                            });

            try {
                runner.await(task);
                fail("expected task timeout");
            } catch (SerialTaskRunner.OperationException expected) {
                assertEquals(
                        SerialTaskRunner.FailureReason.TIMEOUT,
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

        SerialLane lane =
                new SerialLane(
                        4,
                        "rejected",
                        worker);
        SerialTaskRunner runner =
                new SerialTaskRunner(
                        lane,
                        worker,
                        Duration.ofMillis(100));

        runner.start();
        try {
            CompletableFuture<Void> task =
                    runner.runTask(
                            () -> TaskStep.done());

            try {
                runner.await(task);
                fail("expected rejection");
            } catch (SerialTaskRunner.OperationException expected) {
                assertEquals(
                        SerialTaskRunner.FailureReason.OVERLOADED,
                        expected.reason());
            }
        } finally {
            runner.close();
        }
    }
}
