package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeDataLane;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Verifies that LogBook command and ORDERED-query waits use distinct guards. */
public class LogBookDataLaneTest {

    private static final class BlockingOperationItem
            extends LogBookOperationItem<Void> {
        private BlockingOperationItem() {
            super("block", false, false);
        }
    }

    @Test
    public void commandUsesCommitWaitTimeout() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        TimingNodeDataLane lane =
                new TimingNodeDataLane(
                        4,
                        4,
                        "logbook-command-timeout-test",
                        worker);
        lane.bindLogBook(
                operation -> {
                    if (operation instanceof BlockingOperationItem) {
                        blockerStarted.countDown();
                        await(releaseBlocker);
                    }
                });

        LogBookDataLane dataLane =
                new LogBookDataLane(
                        new NodeId("A"),
                        lane,
                        25L,
                        250L);

        dataLane.activate();
        lane.offer(new BlockingOperationItem());
        try {
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            try {
                dataLane.submitCommandAndWait(
                        new LogBookCommand<String>(
                                "commitTest",
                                ignored -> "done"));
                fail("expected command timeout");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("25 ms"));
                assertTrue(expected.getMessage().contains("outcome is unknown"));
            }
        } finally {
            releaseBlocker.countDown();
            dataLane.deactivate();
            worker.shutdownNow();
        }
    }

    @Test
    public void orderedQueryCanBeSubmittedWithoutBlockingCaller()
            throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        TimingNodeDataLane lane =
                new TimingNodeDataLane(
                        4,
                        4,
                        "logbook-query-submit-test",
                        worker);
        lane.bindLogBook(
                operation -> {
                    if (operation instanceof BlockingOperationItem) {
                        blockerStarted.countDown();
                        await(releaseBlocker);
                    } else if (operation instanceof LogBookDataLane.QueryOperationItem<?>) {
                        @SuppressWarnings("unchecked")
                        LogBookDataLane.QueryOperationItem<String> query =
                                (LogBookDataLane.QueryOperationItem<String>) operation;
                        query.complete("done");
                    }
                });

        LogBookDataLane dataLane =
                new LogBookDataLane(
                        new NodeId("A"),
                        lane,
                        250L,
                        250L);

        dataLane.activate();
        lane.offer(new BlockingOperationItem());
        try {
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            CompletableFuture<String> result =
                    dataLane.submitQuery(
                            new LogBookQuery<String>(
                                    "queryTest",
                                    ignored -> "unused"));

            assertFalse(
                    "submitQuery must return while earlier Data Lane work is still blocked",
                    result.isDone());

            releaseBlocker.countDown();

            assertEquals(
                    "done",
                    result.get(1L, TimeUnit.SECONDS));
            assertTrue(result.isDone());
        } finally {
            releaseBlocker.countDown();
            dataLane.deactivate();
            worker.shutdownNow();
        }
    }

    @Test
    public void orderedQueryUsesQueryWaitTimeout() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        TimingNodeDataLane lane =
                new TimingNodeDataLane(
                        4,
                        4,
                        "logbook-query-timeout-test",
                        worker);
        lane.bindLogBook(
                ignored -> {
                    blockerStarted.countDown();
                    await(releaseBlocker);
                });

        LogBookDataLane dataLane =
                new LogBookDataLane(
                        new NodeId("A"),
                        lane,
                        250L,
                        25L);

        dataLane.activate();
        lane.offer(new BlockingOperationItem());
        try {
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            try {
                dataLane.submitQueryAndWait(
                        new LogBookQuery<String>(
                                "queryTest",
                                ignored -> "done"));
                fail("expected query timeout");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("25 ms"));
                assertFalse(expected.getMessage().contains("outcome is unknown"));
            }
        } finally {
            releaseBlocker.countDown();
            dataLane.deactivate();
            worker.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting test", ex);
        }
    }
}
