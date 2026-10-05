package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import java.util.Collections;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimingNodeTest {
    @Test
    public void startsClosedWithoutLocation() {
        NodeId id = new NodeId("TN-01");
        TimingNode node = node(id);

        node.start();
        try {
            TimingNodeTypes.Status status = node.query(TimingNodeQueries.status());

            assertSame(id, status.timingNodeId());
            assertEquals(TimingNodeTypes.Lifecycle.CLOSED, status.lifecycle());
            assertFalse(status.hasLocation());
        } finally {
            node.stop();
        }
    }

    @Test
    public void defaultWorkerThreadNameIncludesConfiguredTimingNodeId() {
        TimingNode node = node(new NodeId("TN-42"));

        node.start();
        try {
            TimingNodeCommand<String> threadName = new TimingNodeCommand<>(
                    "threadName",
                    logic -> Thread.currentThread().getName(),
                    (timingNode, result) -> result);

            assertEquals("tp-dml-node-TN-42", node.invoke(threadName));
        } finally {
            node.stop();
        }
    }

    @Test
    public void openAppliesRequestedLocationAtomically() {
        TimingNode node = node(new NodeId("TN-01"));
        LocationId openLocation = new LocationId(24);

        node.start();
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.invoke(TimingNodeCommands.open(openLocation)));

            TimingNodeTypes.Status openStatus = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.Lifecycle.OPEN, openStatus.lifecycle());
            assertEquals(openLocation, openStatus.locationId());

            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.invoke(TimingNodeCommands.close()));

            TimingNodeTypes.Status closedStatus = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.Lifecycle.CLOSED, closedStatus.lifecycle());
            assertEquals(openLocation, closedStatus.locationId());
        } finally {
            node.stop();
        }
    }

    @Test
    public void openCommandRequiresLocation() {
        try {
            TimingNodeCommands.open(null);
            fail("expected location validation failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("locationId"));
        }
    }

    @Test
    public void repeatedLifecycleCommandsReturnProcessedResults() {
        TimingNode node = node(new NodeId("TN-01"));

        node.start();
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.invoke(TimingNodeCommands.open(new LocationId(24))));
            assertEquals(
                    TimingNodeTypes.OpenResult.ALREADY_OPEN,
                    node.invoke(TimingNodeCommands.open(new LocationId(24))));
            assertEquals(
                    TimingNodeTypes.OpenResult.ALREADY_OPEN,
                    node.invoke(TimingNodeCommands.open(new LocationId(25))));
            TimingNodeTypes.Status submittedStatus = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.Lifecycle.OPEN, submittedStatus.lifecycle());
            assertEquals(new LocationId(24), submittedStatus.locationId());
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.invoke(TimingNodeCommands.close()));
            assertEquals(TimingNodeTypes.CloseResult.ALREADY_CLOSED, node.invoke(TimingNodeCommands.close()));
        } finally {
            node.stop();
        }
    }

    @Test
    public void timeoutDoesNotCancelAcceptedOperation() throws Exception {
        SerialExecutor executor = new SerialExecutor(2, "timing-node-test");
        TimingNode node = node(
                new NodeId("TN-01"),
                executor,
                25L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        node.start();
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            try {
                node.invoke(TimingNodeCommands.open(new LocationId(24)));
                fail("expected timeout");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.TIMEOUT,
                        expected.reason());
            }

            CountDownLatch afterTimedOutOperation = new CountDownLatch(1);
            executor.offer(afterTimedOutOperation::countDown);
            releaseBlocker.countDown();

            assertTrue(afterTimedOutOperation.await(1, TimeUnit.SECONDS));
            TimingNodeTypes.Status status = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.Lifecycle.OPEN, status.lifecycle());
            assertTrue(status.hasLocation());
            assertEquals(new LocationId(24), status.locationId());
        } finally {
            releaseBlocker.countDown();
            node.stop();
        }
    }

    @Test
    public void stateDependentOperationsAreDecidedInQueueOrder() throws Exception {
        SerialExecutor executor = new SerialExecutor(4, "timing-node-test");
        TimingNode node = node(
                new NodeId("TN-01"),
                executor,
                1000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        node.start();
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            final TimingNodeTypes.OpenResult[] openResult = new TimingNodeTypes.OpenResult[1];
            final TimingNodeTypes.CloseResult[] closeResult = new TimingNodeTypes.CloseResult[1];

            Thread openCaller = new Thread(
                    () -> openResult[0] =
                            node.invoke(TimingNodeCommands.open(new LocationId(24))));
            Thread closeCaller = new Thread(
                    () -> closeResult[0] =
                            node.invoke(TimingNodeCommands.close()));

            openCaller.start();
            long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.queueDepth() < 1 && System.nanoTime() < queueDeadline) {
                Thread.yield();
            }
            assertEquals(1, executor.queueDepth());

            closeCaller.start();
            queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.queueDepth() < 2 && System.nanoTime() < queueDeadline) {
                Thread.yield();
            }
            assertEquals(2, executor.queueDepth());

            releaseBlocker.countDown();

            openCaller.join(1000);
            closeCaller.join(1000);
            assertFalse(openCaller.isAlive());
            assertFalse(closeCaller.isAlive());

            assertEquals(TimingNodeTypes.OpenResult.OPENED, openResult[0]);
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, closeResult[0]);
            TimingNodeTypes.Status status = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.Lifecycle.CLOSED, status.lifecycle());
            assertEquals(new LocationId(24), status.locationId());
        } finally {
            releaseBlocker.countDown();
            node.stop();
        }
    }

    @Test
    public void submissionOnlyCommandReturnsAfterAdmissionWithoutWaitingForExecution()
            throws Exception {
        SerialExecutor executor = new SerialExecutor(2, "timing-node-submit-test");
        TimingNode node = node(
                new NodeId("TN-01"),
                executor,
                1000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch producerReturned = new CountDownLatch(1);
        final TimingNodeTypes.CommandAdmission[] admission =
                new TimingNodeTypes.CommandAdmission[1];

        node.start();
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            Thread producer = new Thread(() -> {
                admission[0] =
                        node.submit(
                                TimingNodeCommands.open(
                                        new LocationId(24)));
                producerReturned.countDown();
            });
            producer.start();

            assertTrue(
                    "submission-only producer must not wait for command execution",
                    producerReturned.await(250, TimeUnit.MILLISECONDS));
            assertEquals(
                    TimingNodeTypes.CommandAdmission.ACCEPTED,
                    admission[0]);

            releaseBlocker.countDown();
            producer.join(1000);
            assertFalse(producer.isAlive());

            CountDownLatch afterSubmittedCommand = new CountDownLatch(1);
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    executor.offer(afterSubmittedCommand::countDown));
            assertTrue(afterSubmittedCommand.await(1, TimeUnit.SECONDS));

            assertEquals(
                    new LocationId(24),
                    node.query(TimingNodeQueries.status()).locationId());
        } finally {
            releaseBlocker.countDown();
            node.stop();
        }
    }

    @Test
    public void operationBeforeStartIsUnavailable() {
        TimingNode node = node(new NodeId("TN-01"));

        try {
            node.query(TimingNodeQueries.status());
            fail("expected operation failure");
        } catch (TimingNodeTypes.OperationException expected) {
            assertEquals(
                    TimingNodeTypes.OperationException.Reason.UNAVAILABLE,
                    expected.reason());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingIdentity() {
        new TimingNode(
                null,
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
    }

    private static TimingNode node(NodeId id) {
        return new TimingNode(
                id,
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
    }

    /**
     * Creates a complete node while exposing executor/timeout control only to
     * these boundary tests. Production code never uses this construction path.
     */
    private static TimingNode node(
            NodeId id,
            SerialExecutor executor,
            long timeoutMillis) {
        TimingNodeLogic logic = new TimingNodeLogic(
                id,
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now,
                SystemMonotonicClock.INSTANCE);
        return new TimingNode(logic, executor, timeoutMillis);
    }

    private static TimingTimestamp now() {
        return TimingTimestamp.parse("2026-10-02T08:00:00.000000000Z");
    }

    private static final class NoOpPersistence implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // TimingNodeTest exercises execution/state behaviour, not persistence.
        }
    }
}
