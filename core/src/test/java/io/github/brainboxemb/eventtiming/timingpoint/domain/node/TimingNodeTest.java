package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQuery.ReadConsistency;

import java.time.Instant;
import java.util.Collections;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimingNodeTest {
    private final List<ExecutorService> ownedWorkers =
            new ArrayList<ExecutorService>();

    @After
    public void stopOwnedWorkers() {
        for (ExecutorService worker : ownedWorkers) {
            worker.shutdownNow();
        }
    }

    private SerialExecutor newSerialExecutor(
            int capacity,
            String threadName) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> new Thread(runnable, threadName));
        ownedWorkers.add(worker);
        return new SerialExecutor(capacity, threadName, worker);
    }

    @Test
    public void startsClosedWithoutLocation() {
        NodeId id = new NodeId("A");
        TimingNode node = node(id);

        node.activate();
        try {
            TimingNodeTypes.Status status = node.query(TimingNodeQueries.status());

            assertSame(id, status.timingNodeId());
            assertEquals(TimingNodeTypes.State.CLOSED, status.state());
            assertFalse(status.hasLocation());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void openAppliesRequestedLocationAtomically() {
        TimingNode node = node(new NodeId("A"));
        LocationId openLocation = new LocationId(24);

        node.activate();
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.invoke(TimingNodeCommands.open(openLocation)));

            TimingNodeTypes.Status openStatus = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.State.OPEN, openStatus.state());
            assertEquals(openLocation, openStatus.locationId());

            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.invoke(TimingNodeCommands.close()));

            TimingNodeTypes.Status closedStatus = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.State.CLOSED, closedStatus.state());
            assertEquals(openLocation, closedStatus.locationId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void openAndCloseCommitLifecycleTimingDataInSourceOrder() {
        NodeId id = new NodeId("A");
        RecordingPersistence persistence = new RecordingPersistence();
        TimingNode node = new TimingNode(
                id,
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
        List<TimingData> committedEvents = new ArrayList<TimingData>();
        node.timingDataCommittedEvent().subscribe(committedEvents::add);

        node.activate();
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.invoke(TimingNodeCommands.open(new LocationId(24))));
            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.invoke(TimingNodeCommands.close()));

            assertEquals(2, persistence.records.size());
            assertTrue(persistence.records.get(0) instanceof TimingData.NodeOpen);
            assertTrue(persistence.records.get(1) instanceof TimingData.NodeClose);
            assertEquals(1L, persistence.records.get(0).sequenceNumber());
            assertEquals(2L, persistence.records.get(1).sequenceNumber());
            assertEquals(new LocationId(24), persistence.records.get(0).locationId());
            assertEquals(new LocationId(24), persistence.records.get(1).locationId());
            assertEquals(2, committedEvents.size());
            assertSame(persistence.records.get(0), committedEvents.get(0));
            assertSame(persistence.records.get(1), committedEvents.get(1));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void generatedLifecycleAndRecordedTimesUseConfiguredPrecision() {
        RecordingPersistence persistence = new RecordingPersistence();
        final int[] call = {0};
        final Instant[] times = {
            Instant.parse("2026-10-02T08:00:00.129876543Z"),
            Instant.parse("2026-10-02T08:00:00.134876543Z")
        };
        TimingNode node = new TimingNode(
                new NodeId("A"),
                persistence,
                new DefaultTimingDataFactory(),
                () -> times[Math.min(call[0]++, times.length - 1)]);

        node.activate();
        try {
            node.invoke(
                    TimingNodeCommands.open(
                            new LocationId(24)));

            TimingData open = persistence.records.get(0);
            assertEquals(
                    TimingTimestamp.parse(
                            "2026-10-02T08:00:00.12Z"),
                    open.effectiveTime());
            assertEquals(
                    TimingTimestamp.parse(
                            "2026-10-02T08:00:00.134Z"),
                    open.recordedAt());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void repeatedLifecycleCommandsDoNotCreateAdditionalTimingData() {
        RecordingPersistence persistence = new RecordingPersistence();
        TimingNode node = new TimingNode(
                new NodeId("A"),
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);

        node.activate();
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.invoke(TimingNodeCommands.open(new LocationId(24))));
            assertEquals(
                    TimingNodeTypes.OpenResult.ALREADY_OPEN,
                    node.invoke(TimingNodeCommands.open(new LocationId(25))));
            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.invoke(TimingNodeCommands.close()));
            assertEquals(
                    TimingNodeTypes.CloseResult.ALREADY_CLOSED,
                    node.invoke(TimingNodeCommands.close()));

            assertEquals(2, persistence.records.size());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void failedOpenCommitLeavesNodeClosedAndPublishesNoSuccess() {
        FailingPersistence persistence = new FailingPersistence();
        TimingNode node = new TimingNode(
                new NodeId("A"),
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
        List<TimingData> committedEvents = new ArrayList<TimingData>();
        node.timingDataCommittedEvent().subscribe(committedEvents::add);

        node.activate();
        try {
            try {
                node.invoke(TimingNodeCommands.open(new LocationId(24)));
                fail("expected persistence failure");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            TimingNodeTypes.Status status =
                    node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.State.CLOSED, status.state());
            assertFalse(status.hasLocation());
            assertTrue(committedEvents.isEmpty());
        } finally {
            node.deactivate();
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
    public void repeatedStateCommandsReturnProcessedResults() {
        TimingNode node = node(new NodeId("A"));

        node.activate();
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
            assertEquals(TimingNodeTypes.State.OPEN, submittedStatus.state());
            assertEquals(new LocationId(24), submittedStatus.locationId());
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.invoke(TimingNodeCommands.close()));
            assertEquals(TimingNodeTypes.CloseResult.ALREADY_CLOSED, node.invoke(TimingNodeCommands.close()));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void timeoutDoesNotCancelAcceptedOperation() throws Exception {
        SerialExecutor executor = newSerialExecutor(2, "timing-node-test");
        TimingNode node = node(
                new NodeId("A"),
                executor,
                25L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        node.activate();
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
            assertEquals(TimingNodeTypes.State.OPEN, status.state());
            assertTrue(status.hasLocation());
            assertEquals(new LocationId(24), status.locationId());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }

    @Test
    public void stateDependentOperationsAreDecidedInQueueOrder() throws Exception {
        SerialExecutor executor = newSerialExecutor(4, "timing-node-test");
        TimingNode node = node(
                new NodeId("A"),
                executor,
                1000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        node.activate();
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
            while (executor.metrics().snapshot().queueDepth() < 1 && System.nanoTime() < queueDeadline) {
                Thread.yield();
            }
            assertEquals(1, executor.metrics().snapshot().queueDepth());

            closeCaller.start();
            queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.metrics().snapshot().queueDepth() < 2 && System.nanoTime() < queueDeadline) {
                Thread.yield();
            }
            assertEquals(2, executor.metrics().snapshot().queueDepth());

            releaseBlocker.countDown();

            openCaller.join(1000);
            closeCaller.join(1000);
            assertFalse(openCaller.isAlive());
            assertFalse(closeCaller.isAlive());

            assertEquals(TimingNodeTypes.OpenResult.OPENED, openResult[0]);
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, closeResult[0]);
            TimingNodeTypes.Status status = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.State.CLOSED, status.state());
            assertEquals(new LocationId(24), status.locationId());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }

    @Test
    public void offerReturnsAfterAdmissionWithoutWaitingForExecution()
            throws Exception {
        SerialExecutor executor = newSerialExecutor(2, "timing-node-offer-test");
        TimingNode node = node(
                new NodeId("A"),
                executor,
                1000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch producerReturned = new CountDownLatch(1);
        final TimingNodeTypes.CommandAdmission[] admission =
                new TimingNodeTypes.CommandAdmission[1];

        node.activate();
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            Thread producer = new Thread(() -> {
                admission[0] =
                        node.offer(
                                TimingNodeCommands.open(
                                        new LocationId(24)));
                producerReturned.countDown();
            });
            producer.start();

            assertTrue(
                    "offer-only producer must not wait for command execution",
                    producerReturned.await(250, TimeUnit.MILLISECONDS));
            assertEquals(
                    TimingNodeTypes.CommandAdmission.ACCEPTED,
                    admission[0]);

            releaseBlocker.countDown();
            producer.join(1000);
            assertFalse(producer.isAlive());

            CountDownLatch afterOfferedCommand = new CountDownLatch(1);
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    executor.offer(afterOfferedCommand::countDown));
            assertTrue(afterOfferedCommand.await(1, TimeUnit.SECONDS));

            assertEquals(
                    new LocationId(24),
                    node.query(TimingNodeQueries.status()).locationId());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }


    @Test
    public void currentStatusDoesNotWaitBehindQueuedWork() throws Exception {
        SerialExecutor executor = newSerialExecutor(3, "timing-node-current-test");
        TimingNode node = node(new NodeId("A"), executor, 25L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        node.activate();
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            assertEquals(
                    TimingNodeTypes.CommandAdmission.ACCEPTED,
                    node.offer(TimingNodeCommands.open(new LocationId(24))));

            TimingNodeTypes.Status current =
                    node.query(
                            TimingNodeQueries.status(),
                            ReadConsistency.CURRENT);
            assertEquals(TimingNodeTypes.State.CLOSED, current.state());

            try {
                node.query(
                        TimingNodeQueries.status(),
                        ReadConsistency.ORDERED);
                fail("expected ordered status timeout behind blocked lane");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.TIMEOUT,
                        expected.reason());
            }

            releaseBlocker.countDown();
            CountDownLatch drained = new CountDownLatch(1);
            assertEquals(
                    SerialExecutor.AdmissionResult.ACCEPTED,
                    executor.offer(drained::countDown));
            assertTrue(drained.await(1, TimeUnit.SECONDS));

            assertEquals(
                    TimingNodeTypes.State.OPEN,
                    node.query(
                            TimingNodeQueries.status(),
                            ReadConsistency.CURRENT)
                            .state());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }

    @Test
    public void currentStatusIsPublishedBeforeStatusEvent() {
        TimingNode node = node(new NodeId("A"));
        final TimingNodeTypes.Status[] observedCurrent =
                new TimingNodeTypes.Status[1];

        node.activate();
        try {
            node.statusChangedEvent().subscribe(
                    ignored ->
                            observedCurrent[0] =
                                    node.query(
                                            TimingNodeQueries.status(),
                                            ReadConsistency.CURRENT));

            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            assertEquals(
                    TimingNodeTypes.State.OPEN,
                    observedCurrent[0].state());
            assertEquals(
                    new LocationId(24),
                    observedCurrent[0].locationId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void currentConsistencyRejectsOrderedOnlyLogBookQuery() {
        TimingNode node = node(new NodeId("A"));

        node.activate();
        try {
            try {
                node.query(
                        TimingNodeQueries.timingDataCount(),
                        ReadConsistency.CURRENT);
                fail("expected unsupported CURRENT query");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("CURRENT"));
            }
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void orderedReentrantQueryFailsImmediatelyInsideLogBookVisitor() {
        TimingNode node = node(new NodeId("A"));
        final Throwable[] reentrantFailure = new Throwable[1];
        final TimingNodeTypes.Status[] currentStatus =
                new TimingNodeTypes.Status[1];

        node.activate();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            node.query(
                    TimingNodeQueries.visitTimingDataRange(
                            1L,
                            1,
                            ignored -> {
                                try {
                                    node.query(
                                            TimingNodeQueries.status(),
                                            ReadConsistency.ORDERED);
                                    fail("expected reentrant ORDERED query failure");
                                } catch (IllegalStateException expected) {
                                    reentrantFailure[0] = expected;
                                }

                                currentStatus[0] =
                                        node.query(
                                                TimingNodeQueries.status(),
                                                ReadConsistency.CURRENT);
                            }));

            assertTrue(reentrantFailure[0].getMessage().contains("Reentrant ORDERED"));
            assertEquals(TimingNodeTypes.State.OPEN, currentStatus[0].state());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void currentStatusIsUnavailableOutsideActivation() {
        TimingNode node = node(new NodeId("A"));

        try {
            node.query(
                    TimingNodeQueries.status(),
                    ReadConsistency.CURRENT);
            fail("expected current status to be unavailable before activation");
        } catch (TimingNodeTypes.OperationException expected) {
            assertEquals(
                    TimingNodeTypes.OperationException.Reason.UNAVAILABLE,
                    expected.reason());
        }

        node.activate();
        assertEquals(
                TimingNodeTypes.State.CLOSED,
                node.query(
                        TimingNodeQueries.status(),
                        ReadConsistency.CURRENT)
                        .state());
        node.deactivate();

        try {
            node.query(
                    TimingNodeQueries.status(),
                    ReadConsistency.CURRENT);
            fail("expected current status to be unavailable after deactivation");
        } catch (TimingNodeTypes.OperationException expected) {
            assertEquals(
                    TimingNodeTypes.OperationException.Reason.UNAVAILABLE,
                    expected.reason());
        }
    }

    @Test
    public void invalidSecondActivationDoesNotClearPublishedCurrentStatus() {
        TimingNode node = node(new NodeId("A"));

        node.activate();
        try {
            TimingNodeTypes.Status before =
                    node.query(
                            TimingNodeQueries.status(),
                            ReadConsistency.CURRENT);

            try {
                node.activate();
                fail("expected duplicate activation failure");
            } catch (IllegalStateException expected) {
                // Existing active lifecycle remains authoritative.
            }

            assertSame(
                    before,
                    node.query(
                            TimingNodeQueries.status(),
                            ReadConsistency.CURRENT));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void currentStatusDoesNotHideFailedSerialLane() {
        SerialExecutor executor =
                new SerialExecutor(
                        2,
                        "timing-node-failed-current-test",
                        runnable -> {
                            throw new RejectedExecutionException(
                                    "expected worker rejection");
                        });
        TimingNode node = node(new NodeId("A"), executor, 1000L);

        node.activate();
        try {
            assertEquals(
                    TimingNodeTypes.State.CLOSED,
                    node.query(
                            TimingNodeQueries.status(),
                            ReadConsistency.CURRENT)
                            .state());

            assertEquals(
                    TimingNodeTypes.CommandAdmission.NOT_RUNNING,
                    node.offer(
                            TimingNodeCommands.open(
                                    new LocationId(24))));

            try {
                node.query(
                        TimingNodeQueries.status(),
                        ReadConsistency.CURRENT);
                fail("expected failed lane to invalidate CURRENT status");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void operationBeforeStartIsUnavailable() {
        TimingNode node = node(new NodeId("A"));

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

    private static Instant now() {
        return Instant.parse("2026-10-02T08:00:00Z");
    }

    private static final class RecordingPersistence implements TimingDataPersistence {
        private final List<TimingData> records =
                new ArrayList<TimingData>();

        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            records.add(data);
        }
    }

    private static final class FailingPersistence implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data)
                throws PersistenceException {
            throw new PersistenceException("test append failure");
        }
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
