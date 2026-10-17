package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AbstractTaskTest {

    @Test
    public void wakeWhileRunningCoalescesIntoOneFollowUpRun()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor();
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        8,
                        8,
                        "task-coalesce",
                        worker);
        lane.start();

        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);

        AbstractTask task =
                new AbstractTask() {
                    @Override
                    protected void resetForRun() {
                        // No per-run state.
                    }

                    @Override
                    public TaskStep runStep() {
                        int run = runs.incrementAndGet();
                        if (run == 1) {
                            firstEntered.countDown();
                            try {
                                releaseFirst.await();
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(
                                        "test interrupted",
                                        ex);
                            }
                        } else {
                            secondRan.countDown();
                        }
                        return TaskStep.done();
                    }
                };

        try {
            task.wake(lane);
            assertTrue(
                    firstEntered.await(
                            1L,
                            TimeUnit.SECONDS));

            task.wake(lane);
            task.wake(lane);

            assertEquals(1, runs.get());
            assertTrue(task.isRunning());

            releaseFirst.countDown();

            assertTrue(
                    secondRan.await(
                            1L,
                            TimeUnit.SECONDS));
            await(
                    () -> !task.isRunning(),
                    1000L);

            assertEquals(2, runs.get());
        } finally {
            releaseFirst.countDown();
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void failedWakeDoesNotStartRememberedFollowUp()
            throws Exception {
        ScheduledExecutorService worker =
                Executors.newSingleThreadScheduledExecutor();
        SerialTaskRunner lane =
                new SerialTaskRunner(
                        8,
                        8,
                        "task-failure",
                        worker);
        lane.start();

        AtomicInteger runs = new AtomicInteger();
        AtomicReference<Throwable> observed =
                new AtomicReference<Throwable>();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        CountDownLatch failureObserved = new CountDownLatch(1);
        RuntimeException failure = new RuntimeException("boom");

        AbstractTask task =
                new AbstractTask() {
                    @Override
                    protected void resetForRun() {
                        // No per-run state.
                    }

                    @Override
                    public TaskStep runStep() {
                        runs.incrementAndGet();
                        firstEntered.countDown();
                        try {
                            releaseFailure.await();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(
                                    "test interrupted",
                                    ex);
                        }
                        throw failure;
                    }

                    @Override
                    protected void onRunCompleted(Throwable currentFailure) {
                        observed.set(currentFailure);
                        failureObserved.countDown();
                    }
                };

        try {
            task.wake(lane);
            assertTrue(
                    firstEntered.await(
                            1L,
                            TimeUnit.SECONDS));

            task.wake(lane);
            releaseFailure.countDown();

            assertTrue(
                    failureObserved.await(
                            1L,
                            TimeUnit.SECONDS));
            assertSame(failure, observed.get());
            assertEquals(1, runs.get());
            assertFalse(task.isRunning());
        } finally {
            releaseFailure.countDown();
            lane.close();
            worker.shutdownNow();
        }
    }

    private static void await(
            java.util.function.BooleanSupplier condition,
            long timeoutMillis)
            throws Exception {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "condition did not become true before timeout");
            }
            Thread.sleep(2L);
        }
    }
}
