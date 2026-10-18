package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

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

/** Verifies LogBook Data Lane admission and distinct command/query wait guards. */
public class LogBookDataLaneTest {

    @Test
    public void commandUsesCommitWaitTimeout() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        LogBookDataLane dataLane =
                lane(
                        worker,
                        25L,
                        250L,
                        operation -> {
                            blockerStarted.countDown();
                            await(releaseBlocker);
                        });

        dataLane.activate();
        dataLane.execute(new LogBookCommand<Void>("block", ignored -> null));
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

        LogBookDataLane dataLane =
                lane(
                        worker,
                        250L,
                        250L,
                        operation -> {
                            if (operation instanceof LogBookDataLane.QueryOperationItem<?>) {
                                @SuppressWarnings("unchecked")
                                LogBookDataLane.QueryOperationItem<String> query =
                                        (LogBookDataLane.QueryOperationItem<String>) operation;
                                query.complete("done");
                            } else {
                                blockerStarted.countDown();
                                await(releaseBlocker);
                            }
                        });

        dataLane.activate();
        dataLane.execute(new LogBookCommand<Void>("block", ignored -> null));
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

        LogBookDataLane dataLane =
                lane(
                        worker,
                        250L,
                        25L,
                        ignored -> {
                            blockerStarted.countDown();
                            await(releaseBlocker);
                        });

        dataLane.activate();
        dataLane.execute(new LogBookCommand<Void>("block", ignored -> null));
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

    private static LogBookDataLane lane(
            ExecutorService worker,
            long commandTimeoutMillis,
            long queryTimeoutMillis,
            java.util.function.Consumer<LogBookOperationItem<?>> processor) {

        LogBookDataLane lane =
                new LogBookDataLane(
                        new NodeId("A"),
                        4,
                        4,
                        "logbook-data-test",
                        worker,
                        commandTimeoutMillis,
                        queryTimeoutMillis);
        lane.bind(processor);
        return lane;
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
