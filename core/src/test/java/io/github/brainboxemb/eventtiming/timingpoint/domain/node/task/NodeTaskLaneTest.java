package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NodeTaskLaneTest {
    private ExecutorService worker;

    /** Stops the physical test worker owned by this fixture. */
    @After
    public void stopWorker() {

        if (worker != null) {
            worker.shutdownNow();
        }
    }

    @Test
    public void resultBearingWorkRunsAfterEarlierQueuedTaskLaneWork() {

        NodeTaskLane taskLane = newTaskLane(4);
        AtomicInteger state = new AtomicInteger();

        taskLane.activate();
        try {
            taskLane.execute(() -> state.set(7));

            int result = taskLane.submitAndWait(state::get, "ordered-read");

            assertEquals(7, result);
        } finally {
            taskLane.deactivate();
        }
    }

    @Test
    public void resultBearingWorkRejectsReentrantWaitOnSameLane() throws Exception {

        NodeTaskLane taskLane = newTaskLane(2);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        taskLane.activate();
        try {
            taskLane.execute(() -> {
                try {
                    taskLane.submitAndWait(() -> Integer.valueOf(1), "reentrant-read");
                    failure.set(new AssertionError("reentrant wait was accepted"));
                } catch (IllegalStateException expected) {
                    failure.set(expected);
                } finally {
                    completed.countDown();
                }
            });

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertTrue(failure.get() instanceof IllegalStateException);
        } finally {
            taskLane.deactivate();
        }
    }

    @Test
    public void resultBearingWorkMapsNotRunningLaneToUnavailable() {

        NodeTaskLane taskLane = newTaskLane(1);

        try {
            taskLane.submitAndWait(() -> Integer.valueOf(1), "inactive-read");
            fail("inactive Task Lane accepted work");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not running"));
        }
    }

    /** Creates one NodeTaskLane on a dedicated physical test worker. */
    private NodeTaskLane newTaskLane(int capacity) {

        worker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "node-task-lane-test"));
        return new NodeTaskLane(
                new SerialLane(capacity, capacity, "node-task-lane-test", worker),
                1000L);
    }
}
