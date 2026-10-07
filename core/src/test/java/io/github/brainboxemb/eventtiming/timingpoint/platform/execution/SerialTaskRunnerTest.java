package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialTaskRunnerTest {

    @Test
    public void againYieldsToSiblingLaneWork()
            throws Exception {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        SerialExecutor lane =
                new SerialExecutor(
                        8,
                        "serial-task-test",
                        worker);
        lane.start();

        SerialTaskRunner runner =
                new SerialTaskRunner(
                        lane);
        List<String> order =
                Collections.synchronizedList(
                        new ArrayList<String>());
        CountDownLatch firstStepEntered =
                new CountDownLatch(1);
        CountDownLatch releaseFirstStep =
                new CountDownLatch(1);
        AtomicInteger step =
                new AtomicInteger();

        try {
            CompletableFuture<Void> completion =
                    runner.runTask(
                            () -> {
                                if (step.getAndIncrement() == 0) {
                                    order.add(
                                            "task-1");
                                    firstStepEntered.countDown();
                                    try {
                                        if (!releaseFirstStep.await(
                                                1L,
                                                TimeUnit.SECONDS)) {
                                            throw new IllegalStateException(
                                                    "test did not release first step");
                                        }
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(
                                                "test interrupted",
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
                            500L,
                            TimeUnit.MILLISECONDS));
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    lane.offer(
                            () -> order.add(
                                    "sibling")));

            releaseFirstStep.countDown();
            completion.get(
                    1L,
                    TimeUnit.SECONDS);

            assertEquals(
                    Arrays.asList(
                            "task-1",
                            "sibling",
                            "task-2"),
                    order);
        } finally {
            releaseFirstStep.countDown();
            lane.close();
            worker.shutdownNow();
        }
    }

    @Test
    public void afterRequiresScheduledRunner()
            throws Exception {
        ExecutorService worker =
                Executors.newSingleThreadExecutor();
        SerialExecutor lane =
                new SerialExecutor(
                        4,
                        "serial-task-delay-test",
                        worker);
        lane.start();

        try {
            CompletableFuture<Void> completion =
                    new SerialTaskRunner(
                            lane)
                            .runTask(
                                    () -> TaskStep.after(
                                            Duration.ofMillis(
                                                    1L)));

            try {
                completion.get(
                        1L,
                        TimeUnit.SECONDS);
                fail(
                        "expected AFTER rejection");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(
                        expected.getCause()
                                instanceof IllegalStateException);
            }
        } finally {
            lane.close();
            worker.shutdownNow();
        }
    }
}
