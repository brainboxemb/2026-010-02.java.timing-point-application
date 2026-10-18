package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

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

    @Test
    public void commandUsesCommitWaitTimeout() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SerialLane lane = new SerialLane(4, 4, "logbook-command-timeout-test", worker);
        LogBookDataLane dataLane =
                new LogBookDataLane(
                        new NodeId("A"),
                        lane,
                        25L,
                        250L,
                        ignored -> { });
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        dataLane.activate(() -> { });
        try {
            lane.submit(
                    () -> {
                        blockerStarted.countDown();
                        releaseBlocker.await();
                        return null;
                    });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

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
    public void orderedQueryUsesQueryWaitTimeout() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SerialLane lane = new SerialLane(4, 4, "logbook-query-timeout-test", worker);
        LogBookDataLane dataLane =
                new LogBookDataLane(
                        new NodeId("A"),
                        lane,
                        250L,
                        25L,
                        ignored -> { });
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        dataLane.activate(() -> { });
        try {
            lane.submit(
                    () -> {
                        blockerStarted.countDown();
                        releaseBlocker.await();
                        return null;
                    });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

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
}
