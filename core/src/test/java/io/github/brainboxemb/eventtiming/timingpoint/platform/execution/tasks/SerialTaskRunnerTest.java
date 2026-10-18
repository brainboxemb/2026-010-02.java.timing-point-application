package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialTaskRunnerTest {

    @Test
    public void againYieldsToAlreadyQueuedWork()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialTaskRunner lane =
                new SerialTaskRunner(
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

            lane.runTask(
                    () -> {
                        order.add("sibling");
                        return TaskStep.done();
                    });

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
        SerialTaskRunner taskLane =
                new SerialTaskRunner(
                        8,
                        8,
                        "yielding-delay",
                        worker,
                        Duration.ofSeconds(1));
        SerialLane<Runnable> siblingLane =
                new SerialLane<Runnable>(
                        8,
                        8,
                        "yielding-sibling",
                        worker,
                        Runnable::run);
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
            assertEquals(
                    SerialLane.OfferResult.ACCEPTED,
                    siblingLane.offer(siblingRan::countDown));
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
    public void awaitReleasesPhysicalWorkerAndResumesAfterCompletion()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialTaskRunner taskLane =
                new SerialTaskRunner(
                        8,
                        8,
                        "yielding-await",
                        worker,
                        Duration.ofSeconds(1));
        SerialLane<Runnable> siblingLane =
                new SerialLane<Runnable>(
                        8,
                        8,
                        "yielding-await-sibling",
                        worker,
                        Runnable::run);
        taskLane.start();
        siblingLane.start();

        CompletableFuture<String> dependency =
                new CompletableFuture<String>();
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
                                    return TaskStep.await(dependency);
                                }

                                assertEquals(
                                        "ready",
                                        dependency.join());
                                return TaskStep.done();
                            });

            assertTrue(
                    waiting.await(
                            500,
                            TimeUnit.MILLISECONDS));

            CountDownLatch siblingRan =
                    new CountDownLatch(1);
            assertEquals(
                    SerialLane.OfferResult.ACCEPTED,
                    siblingLane.offer(siblingRan::countDown));
            assertTrue(
                    "awaiting task must release the shared worker",
                    siblingRan.await(
                            75,
                            TimeUnit.MILLISECONDS));

            assertEquals(
                    "task must not run another step before dependency completion",
                    1,
                    step.get());

            dependency.complete("ready");
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
        SerialTaskRunner lane =
                new SerialTaskRunner(
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
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void timeoutInterruptDoesNotLeakToNextTask()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        4,
                        4,
                        "timeout-interrupt-isolation",
                        worker,
                        Duration.ofMillis(50));
        lane.start();

        CountDownLatch firstStarted =
                new CountDownLatch(1);
        CountDownLatch secondRan =
                new CountDownLatch(1);
        AtomicInteger secondSawInterrupt =
                new AtomicInteger();

        try {
            CompletableFuture<Void> first =
                    lane.runTask(
                            () -> {
                                firstStarted.countDown();
                                try {
                                    Thread.sleep(
                                            TimeUnit.SECONDS.toMillis(10));
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(
                                            "interrupted",
                                            ex);
                                }
                                return TaskStep.done();
                            });

            assertTrue(
                    firstStarted.await(
                            500,
                            TimeUnit.MILLISECONDS));

            lane.runTask(
                    () -> {
                        if (Thread.currentThread().isInterrupted()) {
                            secondSawInterrupt.incrementAndGet();
                        }
                        secondRan.countDown();
                        return TaskStep.done();
                    });

            try {
                lane.await(first);
                fail("expected task timeout");
            } catch (SerialTaskRunner.OperationException expected) {
                assertEquals(
                        SerialTaskRunner.FailureReason.TIMEOUT,
                        expected.reason());
            }

            assertTrue(
                    secondRan.await(
                            500,
                            TimeUnit.MILLISECONDS));
            assertEquals(
                    "task cancellation interrupt leaked to the next queued task",
                    0,
                    secondSawInterrupt.get());
        } finally {
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void closeDoesNotWaitForRunningTaskStep()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        SerialTaskRunner runner =
                new SerialTaskRunner(
                        4,
                        4,
                        "non-blocking-close",
                        worker,
                        Duration.ofSeconds(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runner.start();

        runner.runTask(
                () -> {
                    started.countDown();
                    boolean interrupted = false;
                    while (release.getCount() != 0L) {
                        try {
                            release.await();
                        } catch (InterruptedException ex) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return TaskStep.done();
                });

        assertTrue(started.await(500L, TimeUnit.MILLISECONDS));

        Thread closer = new Thread(runner::close);
        closer.start();
        closer.join(250L);

        assertTrue(
                "close() must return without waiting for the running task step",
                !closer.isAlive());

        release.countDown();
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(1L, TimeUnit.SECONDS));
    }
    @Test
    public void rejectedBackingWorkerRejectsInitialAdmission() {
        ScheduledExecutorService worker =
                Executors.newScheduledThreadPool(1);
        worker.shutdownNow();

        SerialTaskRunner runner =
                new SerialTaskRunner(
                        4,
                        4,
                        "rejected",
                        worker,
                        Duration.ofMillis(100));

        runner.start();
        try {
            try {
                runner.runTask(() -> TaskStep.done());
                fail("expected rejection");
            } catch (RejectedExecutionException expected) {
                // Initial admission reports the JDK executor rejection directly.
            }
        } finally {
            runner.close();
        }
    }
}
