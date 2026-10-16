package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookRecoveryStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;

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
import static org.junit.Assert.assertNull;
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

    private SerialLane newSerialLane(
            int capacity,
            String threadName) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> new Thread(runnable, threadName));
        ownedWorkers.add(worker);
        return new SerialLane(capacity, threadName, worker);
    }

    @Test
    public void startsClosedWithoutLocation() {
        NodeId id = new NodeId("A");
        TimingNode node = node(id);

        node.activate();
        try {
            TimingNodeTypes.Status status = (TimingNodeTypes.Status) node.status();

            assertEquals(TimingNodeTypes.State.CLOSED, status.state());
            assertFalse(status.hasLocation());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void timingNodePublishesItsStatusAfterOpenAndClose() {
        TimingNode node = node(new NodeId("A"));
        List<TimingNodeTypes.Status> changes =
                new ArrayList<TimingNodeTypes.Status>();

        node.STATUS_CHANGED.subscribe(
                status -> changes.add((TimingNodeTypes.Status) status));
        node.activate();
        try {
            assertEquals(TimingNodeTypes.State.CLOSED, ((TimingNodeTypes.Status) node.status()).state());

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(TimingNodeTypes.State.OPEN, ((TimingNodeTypes.Status) node.status()).state());
            assertEquals(new LocationId(24), ((TimingNodeTypes.Status) node.status()).locationId());

            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.close());
            assertEquals(TimingNodeTypes.State.CLOSED, ((TimingNodeTypes.Status) node.status()).state());

            assertEquals(3, changes.size());
            assertEquals(TimingNodeTypes.State.CLOSED, changes.get(0).state());
            assertEquals(TimingNodeTypes.State.OPEN, changes.get(1).state());
            assertEquals(TimingNodeTypes.State.CLOSED, changes.get(2).state());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void activationLoadsPersistedDataOnDataLaneAndReturnsBeforeLoadCompletes()
            throws Exception {
        SerialLane executor =
                newSerialLane(
                        4,
                        "timing-node-load-worker");
        BlockingPersistence persistence =
                new BlockingPersistence(executor);
        TimingNode node =
                node(
                        new NodeId("A"),
                        persistence,
                        executor,
                        1000L);
        CountDownLatch firstStatusPublished = new CountDownLatch(1);
        node.STATUS_CHANGED.subscribe(
                status -> firstStatusPublished.countDown());

        final Throwable[] activationFailure = new Throwable[1];
        Thread activationCaller = new Thread(
                () -> {
                    try {
                        node.activate();
                    } catch (Throwable failure) {
                        activationFailure[0] = failure;
                    }
                },
                "timing-node-activation-caller");

        activationCaller.start();
        try {
            assertTrue(
                    "persisted-data load did not start",
                    persistence.loadStarted.await(
                            1L,
                            TimeUnit.SECONDS));
            activationCaller.join(250L);
            assertFalse(
                    "activate() waited for persisted-data load",
                    activationCaller.isAlive());
            assertNull(activationFailure[0]);
            assertTrue(
                    "persisted-data load did not run on the Data Lane",
                    persistence.executedOnNodeLane);

            try {
                node.status();
                fail("TimingNode status must remain unavailable during persisted-data load");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.UNAVAILABLE,
                        expected.reason());
            }

            final TimingNodeTypes.OpenResult[] openResult =
                    new TimingNodeTypes.OpenResult[1];
            Thread openCaller = new Thread(
                    () -> openResult[0] = node.open(new LocationId(24)),
                    "timing-node-open-caller");
            openCaller.start();
            openCaller.join(100L);
            assertTrue(
                    "open() must remain ordered behind persisted-data recovery",
                    openCaller.isAlive());

            persistence.releaseLoad.countDown();
            assertTrue(
                    "initial status was not published after persisted-data load",
                    firstStatusPublished.await(
                            1L,
                            TimeUnit.SECONDS));
            openCaller.join(1000L);
            assertFalse(openCaller.isAlive());

            assertEquals(TimingNodeTypes.OpenResult.OPENED, openResult[0]);
            assertEquals(TimingNodeTypes.State.OPEN, ((TimingNodeTypes.Status) node.status()).state());
        } finally {
            persistence.releaseLoad.countDown();
            activationCaller.join(1000L);
            node.deactivate();
        }
    }

    @Test
    public void failedPersistedDataLoadPublishesInitialErrorStatus() throws Exception {
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                new FailingLoadPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
        CountDownLatch initialStatusPublished = new CountDownLatch(1);
        node.STATUS_CHANGED.subscribe(
                status -> initialStatusPublished.countDown());

        node.activate();
        try {
            assertTrue(
                    "initial ERROR status was not published after failed persisted-data load",
                    initialStatusPublished.await(1L, TimeUnit.SECONDS));

            assertTrue(node.status() instanceof TimingNodeTypes.ErrorStatus);
            TimingNodeTypes.ErrorStatus current =
                    (TimingNodeTypes.ErrorStatus) node.status();
            assertEquals(
                    TimingNodeTypes.ErrorCode.TIMING_DATA_LOAD_FAILED,
                    current.code());
            assertTrue(current.message().contains("Persisted TimingData load failed"));
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
                    node.open(openLocation));

            TimingNodeTypes.Status openStatus = (TimingNodeTypes.Status) node.status();
            assertEquals(TimingNodeTypes.State.OPEN, openStatus.state());
            assertEquals(openLocation, openStatus.locationId());

            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.close());

            TimingNodeTypes.Status closedStatus = (TimingNodeTypes.Status) node.status();
            assertEquals(TimingNodeTypes.State.CLOSED, closedStatus.state());
            assertEquals(openLocation, closedStatus.locationId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void openAndCloseCommitOperationStateTimingDataInSourceOrder() {
        NodeId id = new NodeId("A");
        RecordingPersistence persistence = new RecordingPersistence();
        TimingNode node = TimingNodeFixture.createDirect(
                id,
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
        List<TimingData> committedEvents = new ArrayList<TimingData>();
        node.logBook().newDataEntryEvent().subscribe(committedEvents::add);

        node.activate();
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.close());

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
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                persistence,
                new DefaultTimingDataFactory(),
                () -> times[Math.min(call[0]++, times.length - 1)]);

        node.activate();
        try {
            node.open(new LocationId(24));

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
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);

        node.activate();
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.OpenResult.ALREADY_OPEN,
                    node.open(new LocationId(25)));
            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.close());
            assertEquals(
                    TimingNodeTypes.CloseResult.ALREADY_CLOSED,
                    node.close());

            assertEquals(2, persistence.records.size());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void failedOpenCommitLeavesNodeClosedAndPublishesNoSuccess() {
        FailingPersistence persistence = new FailingPersistence();
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
        List<TimingData> committedEvents = new ArrayList<TimingData>();
        node.logBook().newDataEntryEvent().subscribe(committedEvents::add);

        node.activate();
        try {
            try {
                node.open(new LocationId(24));
                fail("expected persistence failure");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            TimingNodeTypes.Status status =
                    (TimingNodeTypes.Status) node.status();
            assertEquals(TimingNodeTypes.State.CLOSED, status.state());
            assertFalse(status.hasLocation());
            assertTrue(committedEvents.isEmpty());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void openRequiresLocation() {
        try {
            node(new NodeId("A")).open(null);
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
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.OpenResult.ALREADY_OPEN,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.OpenResult.ALREADY_OPEN,
                    node.open(new LocationId(25)));
            TimingNodeTypes.Status submittedStatus = (TimingNodeTypes.Status) node.status();
            assertEquals(TimingNodeTypes.State.OPEN, submittedStatus.state());
            assertEquals(new LocationId(24), submittedStatus.locationId());
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.close());
            assertEquals(TimingNodeTypes.CloseResult.ALREADY_CLOSED, node.close());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void timeoutDoesNotCancelAcceptedOperation() throws Exception {
        SerialLane executor = newSerialLane(2, "timing-node-test");
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
                node.open(new LocationId(24));
                fail("expected timeout");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.TIMEOUT,
                        expected.reason());
            }

            CountDownLatch afterTimedOutOperation = new CountDownLatch(1);
            executor.execute(afterTimedOutOperation::countDown);
            releaseBlocker.countDown();

            assertTrue(afterTimedOutOperation.await(1, TimeUnit.SECONDS));
            TimingNodeTypes.Status status = (TimingNodeTypes.Status) node.status();
            assertEquals(TimingNodeTypes.State.OPEN, status.state());
            assertTrue(status.hasLocation());
            assertEquals(new LocationId(24), status.locationId());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }

    @Test
    public void concurrentOperationStateChangesRemainOrderedByTimingNode() throws Exception {
        SerialLane executor = newSerialLane(4, "timing-node-test");
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
                    () -> openResult[0] = node.open(new LocationId(24)));
            Thread closeCaller = new Thread(
                    () -> closeResult[0] = node.close());

            openCaller.start();
            long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (executor.metrics().snapshot().queueDepth() < 1 && System.nanoTime() < queueDeadline) {
                Thread.yield();
            }
            assertEquals(1, executor.metrics().snapshot().queueDepth());

            closeCaller.start();
            assertEquals(
                    "close waits behind the in-flight node operation-state change",
                    1,
                    executor.metrics().snapshot().queueDepth());

            releaseBlocker.countDown();

            openCaller.join(1000);
            closeCaller.join(1000);
            assertFalse(openCaller.isAlive());
            assertFalse(closeCaller.isAlive());

            assertEquals(TimingNodeTypes.OpenResult.OPENED, openResult[0]);
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, closeResult[0]);
            TimingNodeTypes.Status status = (TimingNodeTypes.Status) node.status();
            assertEquals(TimingNodeTypes.State.CLOSED, status.state());
            assertEquals(new LocationId(24), status.locationId());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }

    /** Verifies that LogBook.queue returns after lane admission without waiting for command completion. */
    @Test
    public void queueReturnsAfterAdmissionWithoutWaitingForExecution()
            throws Exception {
        SerialLane executor = newSerialLane(2, "timing-node-offer-test");
        TimingNode node = node(
                new NodeId("A"),
                executor,
                1000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch producerReturned = new CountDownLatch(1);
        node.activate();
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            Thread producer = new Thread(() -> {
                node.logBook().execute(
                        LogBookCommands.commitNodeOpen(
                                new LocationId(24)));
                producerReturned.countDown();
            });
            producer.start();

            assertTrue(
                    "offer-only producer must not wait for command execution",
                    producerReturned.await(250, TimeUnit.MILLISECONDS));
            releaseBlocker.countDown();
            producer.join(1000);
            assertFalse(producer.isAlive());

            CountDownLatch afterOfferedCommand = new CountDownLatch(1);
            executor.execute(afterOfferedCommand::countDown);
            assertTrue(afterOfferedCommand.await(1, TimeUnit.SECONDS));

            assertEquals(
                    new LocationId(24),
                    ((TimingNodeTypes.Status) node.status()).locationId());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }


    @Test
    public void currentStatusDoesNotWaitBehindQueuedWork() throws Exception {
        SerialLane executor = newSerialLane(3, "timing-node-current-test");
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

            node.logBook().execute(
                    LogBookCommands.commitNodeOpen(new LocationId(24)));

            assertEquals(TimingNodeTypes.State.CLOSED, ((TimingNodeTypes.Status) node.status()).state());

            try {
                node.logBook().process(
                        LogBookQueries.recoveryStatus(),
                        ReadConsistency.ORDERED);
                fail("expected ordered recovery query timeout behind blocked lane");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.TIMEOUT,
                        expected.reason());
            }

            releaseBlocker.countDown();
            CountDownLatch drained = new CountDownLatch(1);
            executor.execute(drained::countDown);
            assertTrue(drained.await(1, TimeUnit.SECONDS));

            assertEquals(TimingNodeTypes.State.OPEN, ((TimingNodeTypes.Status) node.status()).state());
        } finally {
            releaseBlocker.countDown();
            node.deactivate();
        }
    }

    @Test
    public void currentStatusIsPublishedBeforePostCommandEvents() {
        TimingNode node = node(new NodeId("A"));
        final TimingNodeTypes.Status[] statusEventCurrent =
                new TimingNodeTypes.Status[1];
        final TimingNodeTypes.Status[] timingDataEventCurrent =
                new TimingNodeTypes.Status[1];

        node.activate();
        try {
            node.STATUS_CHANGED.subscribe(
                    ignored -> statusEventCurrent[0] = (TimingNodeTypes.Status) node.status());
            node.logBook().newDataEntryEvent().subscribe(
                    ignored -> timingDataEventCurrent[0] = (TimingNodeTypes.Status) node.status());

            node.open(new LocationId(24));

            assertEquals(
                    TimingNodeTypes.State.OPEN,
                    timingDataEventCurrent[0].state());
            assertEquals(
                    new LocationId(24),
                    timingDataEventCurrent[0].locationId());
            assertEquals(
                    TimingNodeTypes.State.OPEN,
                    statusEventCurrent[0].state());
            assertEquals(
                    new LocationId(24),
                    statusEventCurrent[0].locationId());
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
                node.logBook().process(
                        LogBookQueries.timingDataCount(),
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
    public void orderedRecoveryQueryReadsDirectlyInsideLogBookVisitor() {
        TimingNode node = node(new NodeId("A"));
        final LogBookRecoveryStatus[] orderedStatus =
                new LogBookRecoveryStatus[1];
        final LogBookRecoveryStatus[] currentStatus =
                new LogBookRecoveryStatus[1];

        node.activate();
        try {
            node.open(new LocationId(24));

            node.logBook().process(
                    LogBookQueries.visitTimingDataRange(
                            1L,
                            1,
                            ignored -> {
                                orderedStatus[0] =
                                        node.logBook().process(
                                                LogBookQueries.recoveryStatus(),
                                                ReadConsistency.ORDERED);
                                currentStatus[0] =
                                        node.logBook().process(
                                                LogBookQueries.recoveryStatus(),
                                                ReadConsistency.CURRENT);
                            }));

            assertFalse(orderedStatus[0].failed());
            assertFalse(currentStatus[0].failed());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void currentStatusIsUnavailableOutsideActivation() {
        TimingNode node = node(new NodeId("A"));

        try {
            node.status();
            fail("expected current status to be unavailable before activation");
        } catch (TimingNodeTypes.OperationException expected) {
            assertEquals(
                    TimingNodeTypes.OperationException.Reason.UNAVAILABLE,
                    expected.reason());
        }

        node.activate();
        assertEquals(
                TimingNodeTypes.State.CLOSED,
                ((TimingNodeTypes.Status) node.status()).state());
        node.deactivate();

        try {
            node.status();
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
            TimingNodeTypes.Status before = (TimingNodeTypes.Status) node.status();

            try {
                node.activate();
                fail("expected duplicate activation failure");
            } catch (IllegalStateException expected) {
                // Existing active lifecycle remains authoritative.
            }

            assertSame(before, node.status());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void activationFailsWhenPersistedDataLoadCannotBeQueued() {
        SerialLane executor =
                new SerialLane(
                        2,
                        "timing-node-failed-current-test",
                        runnable -> {
                            throw new RejectedExecutionException(
                                    "expected worker rejection");
                        });
        TimingNode node = node(new NodeId("A"), executor, 1000L);

        try {
            node.activate();
            fail("expected activation failure");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage()
                            .contains("Persisted TimingData load"));
        } finally {
            node.deactivate();
        }

        try {
            node.status();
            fail("failed activation must not publish CURRENT status");
        } catch (TimingNodeTypes.OperationException expected) {
            assertEquals(
                    TimingNodeTypes.OperationException.Reason.FAILED,
                    expected.reason());
        }
    }

    @Test
    public void operationBeforeStartIsUnavailable() {
        TimingNode node = node(new NodeId("A"));

        try {
            node.status();
            fail("expected operation failure");
        } catch (TimingNodeTypes.OperationException expected) {
            assertEquals(
                    TimingNodeTypes.OperationException.Reason.UNAVAILABLE,
                    expected.reason());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingIdentity() {
        TimingNodeFixture.createDirect(
                null,
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
    }

    private static TimingNode node(NodeId id) {
        return TimingNodeFixture.createDirect(
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
            SerialLane executor,
            long timeoutMillis) {
        return TimingNodeFixture.createWithDataLane(
                id,
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now,
                executor,
                timeoutMillis);
    }

    private static TimingNode node(
            NodeId id,
            TimingDataPersistence persistence,
            SerialLane executor,
            long timeoutMillis) {
        return TimingNodeFixture.createWithDataLane(
                id,
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now,
                executor,
                timeoutMillis);
    }

    private static Instant now() {
        return Instant.parse("2026-10-02T08:00:00Z");
    }

    private static final class BlockingPersistence implements TimingDataPersistence {
        private final SerialLane expectedLane;
        private final CountDownLatch loadStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseLoad =
                new CountDownLatch(1);
        private volatile boolean executedOnNodeLane;

        private BlockingPersistence(
                SerialLane expectedLane) {
            this.expectedLane = expectedLane;
        }

        @Override
        public LoadResult load()
                throws PersistenceException {
            executedOnNodeLane =
                    expectedLane.isExecutingOnCurrentThread();
            loadStarted.countDown();
            try {
                releaseLoad.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new PersistenceException(
                        "test persisted-data load interrupted",
                        ex);
            }
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // Startup-load/lane ownership test only.
        }
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

    private static final class FailingLoadPersistence implements TimingDataPersistence {
        @Override
        public LoadResult load()
                throws PersistenceException {
            throw new PersistenceException("test load failure");
        }

        @Override
        public void append(TimingData data) {
            // Failed startup-load test only.
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
