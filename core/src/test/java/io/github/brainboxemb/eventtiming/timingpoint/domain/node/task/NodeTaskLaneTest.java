package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class NodeTaskLaneTest {
    private ExecutorService worker;

    private static final class TestItem extends NodeTaskLane.Item<Integer> {
        private final int value;

        private TestItem(String name, boolean resultBearing, int value) {
            super(name, resultBearing);
            this.value = value;
        }
    }

    /** Stops the physical test worker owned by this fixture. */
    @After
    public void stopWorker() {
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    @Test
    public void resultBearingItemRunsAfterEarlierQueuedTaskLaneItem() {
        AtomicInteger state = new AtomicInteger();
        NodeTaskLane taskLane =
                newTaskLane(
                        4,
                        item -> {
                            TestItem testItem = (TestItem) item;
                            if ("set".equals(testItem.name())) {
                                state.set(testItem.value);
                            } else if ("read".equals(testItem.name())) {
                                testItem.complete(Integer.valueOf(state.get()));
                            }
                        });

        taskLane.activate();
        try {
            taskLane.execute(new TestItem("set", false, 7));

            int result =
                    taskLane.submitAndWait(
                            new TestItem("read", true, 0));

            assertEquals(7, result);
        } finally {
            taskLane.deactivate();
        }
    }

    @Test
    public void resultBearingItemRejectsReentrantWaitOnSameLane()
            throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        AtomicReference<NodeTaskLane> laneRef = new AtomicReference<NodeTaskLane>();

        NodeTaskLane taskLane =
                newTaskLane(
                        2,
                        item -> {
                            try {
                                laneRef.get()
                                        .submitAndWait(
                                                new TestItem(
                                                        "reentrant-read",
                                                        true,
                                                        0));
                                failure.set(
                                        new AssertionError(
                                                "reentrant wait was accepted"));
                            } catch (IllegalStateException expected) {
                                failure.set(expected);
                            } finally {
                                completed.countDown();
                            }
                        });
        laneRef.set(taskLane);

        taskLane.activate();
        try {
            taskLane.execute(new TestItem("trigger", false, 0));

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertTrue(failure.get() instanceof IllegalStateException);
        } finally {
            taskLane.deactivate();
        }
    }

    @Test
    public void resultBearingItemMapsNotRunningLaneToUnavailable() {
        NodeTaskLane taskLane = newTaskLane(1, ignored -> { });

        try {
            taskLane.submitAndWait(
                    new TestItem("inactive-read", true, 0));
            fail("inactive Task Lane accepted item");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not running"));
        }
    }

    /** Creates one NodeTaskLane on a dedicated physical test worker. */
    private NodeTaskLane newTaskLane(
            int capacity,
            Consumer<NodeTaskLane.Item<?>> itemProcessor) {

        worker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "node-task-lane-test"));
        return new NodeTaskLane(
                new SerialLane(capacity, capacity, "node-task-lane-test", worker),
                1000L,
                itemProcessor);
    }
}
