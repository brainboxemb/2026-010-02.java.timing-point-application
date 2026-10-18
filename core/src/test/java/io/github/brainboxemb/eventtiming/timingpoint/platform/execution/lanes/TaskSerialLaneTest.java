package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TaskSerialLaneTest {

    @Test
    public void againYieldsToAlreadyQueuedWork()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        TaskSerialLane lane =
                new TaskSerialLane(
                        8,
                        8,
                        "yielding-yield",
                        worker,
                        Duration.ofSeconds(1));
        lane.start();

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
                    lane.runTask(
                            () -> {
                                if (step.getAndIncrement() == 0) {
                                    order.add("task-1");
                                    firstStepEntered.countDown();
                                    try {
                                        if (!allowFirstStepReturn.await(
                                                1,
                                                TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "test did not release first step");
                                        }
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(
                                                "test task interrupted",
                                                ex);
                                    }
                                    return TaskStep.again();
                                }

                                order.add("task-2");
                                return TaskStep.done();
                            });

            assertTrue(
                    firstStepEntered.await(
                            500,
                            TimeUnit.MILLISECONDS));

            lane.execute(() -> order.add("sibling"));

            allowFirstStepReturn.countDown();
            lane.await(task);

            assertEquals(
                    Arrays.asList(
                            "task-1",
                            "sibling",
                            "task-2"),
                    order);
        } finally {
            allowFirstStepReturn.countDown();
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void afterReleasesPhysicalWorker()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        TaskSerialLane taskLane =
                new TaskSerialLane(
                        8,
                        8,
                        "yielding-delay",
                        worker,
                        Duration.ofSeconds(1));
        RunnableSerialLane siblingLane =
                new RunnableSerialLane(
                        8,
                        8,
                        "yielding-sibling",
                        worker);
        taskLane.start();
        siblingLane.start();

        AtomicInteger step =
                new AtomicInteger();
        CountDownLatch waiting =
                new CountDownLatch(1);

        try {
            CompletableFuture<Void> task =
                    taskLane.runTask(
                            () -> {
                                if (step.getAndIncrement() == 0) {
                                    waiting.countDown();
                                    return TaskStep.after(
                                            Duration.ofMillis(200));
                                }
                                return TaskStep.done();
                            });

            assertTrue(
                    waiting.await(
                            500,
                            TimeUnit.MILLISECONDS));

            CountDownLatch siblingRan =
                    new CountDownLatch(1);
            siblingLane.execute(siblingRan::countDown);
            assertTrue(
                    "yielding delay must release the shared worker",
                    siblingRan.await(
                            75,
                            TimeUnit.MILLISECONDS));

            taskLane.await(task);
            assertEquals(2, step.get());
        } finally {
            taskLane.close();
            siblingLane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void timeoutInterruptsRunningTask()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        TaskSerialLane lane =
                new TaskSerialLane(
                        4,
                        4,
                        "timeout",
                        worker,
                        Duration.ofMillis(50));
        lane.start();

        CountDownLatch interrupted =
                new CountDownLatch(1);

        try {
            CompletableFuture<Void> task =
                    lane.runTask(
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
                lane.await(task);
                fail("expected task timeout");
            } catch (TaskSerialLane.OperationException expected) {
                assertEquals(
                        TaskSerialLane.FailureReason.TIMEOUT,
                        expected.reason());
            }

            assertTrue(
                    "timed-out task must be interrupted",
                    interrupted.await(
                            500,
                            TimeUnit.MILLISECONDS));
        } finally {
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void rejectedBackingWorkerIsOverloaded() {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        worker.shutdownNow();

        TaskSerialLane lane =
                new TaskSerialLane(
                        4,
                        4,
                        "rejected",
                        worker,
                        Duration.ofMillis(100));

        lane.start();
        try {
            CompletableFuture<Void> task =
                    lane.runTask(
                            () -> TaskStep.done());

            try {
                lane.await(task);
                fail("expected rejection");
            } catch (TaskSerialLane.OperationException expected) {
                assertEquals(
                        TaskSerialLane.FailureReason.OVERLOADED,
                        expected.reason());
            }
        } finally {
            lane.close();
        }
    }
}
