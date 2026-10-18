package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.RunnableSerialLane;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    private static final class TestItem implements NodeTaskLane.ResultItem<Integer> {
        private final String name;
        private final int value;
        private final CompletableFuture<Integer> result =
                new CompletableFuture<Integer>();

        private TestItem(String name, int value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Future<Integer> result() {
            return result;
        }

        private void complete(int value) {
            result.complete(Integer.valueOf(value));
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
                                testItem.complete(state.get());
                            }
                        });

        taskLane.activate();
        try {
            taskLane.execute(new TestItem("set", 7));

            int result =
                    taskLane.submitAndWait(
                            new TestItem("read", 0));

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
            taskLane.execute(new TestItem("trigger", 0));

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
                    new TestItem("inactive-read", 0));
            fail("inactive Task Lane accepted item");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not running"));
        }
    }

    /** Creates one NodeTaskLane on a dedicated physical test worker. */
    private NodeTaskLane newTaskLane(
            int capacity,
            Consumer<NodeTaskLane.Item> itemProcessor) {

        worker = Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "node-task-lane-test"));
        return new NodeTaskLane(
                new RunnableSerialLane(capacity, capacity, "node-task-lane-test", worker),
                1000L,
                itemProcessor);
    }
}
