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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookTestControl;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookRecoveryStatus;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.Property;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.PropertyUpdate;

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

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
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
        return new SerialLane(capacity, capacity, threadName, worker);
    }

    @Test
    public void startsClosedWithoutLocation() {
        NodeId id = new NodeId("A");
        TimingNode node = node(id);

        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());
            assertFalse(node.locationId() != null);
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void timingNodePublishesFunctionalStateAfterOpenAndClose() {
        TimingNode node = node(new NodeId("A"));
        List<PropertyUpdate> changes =
                new ArrayList<PropertyUpdate>();

        node.PROPERTIES.CHANGED.subscribe(
                changed -> {
                    if (changedProperty(changed, "functionalState")) {
                        changes.add(changed);
                    }
                });
        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());

            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.close());
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());

            assertEquals(2, changes.size());
            assertTrue(changedProperty(changes.get(0), "functionalState"));
            assertTrue(changedProperty(changes.get(0), "locationId"));
            assertTrue(changedProperty(changes.get(1), "functionalState"));
        } finally {
            deactivate(node);
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
        final Throwable[] activationFailure = new Throwable[1];
        Thread activationCaller = new Thread(
                () -> {
                    try {
                        activate(node);
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

            assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
            assertNull(node.locationId());

            final TimingNodeTypes.OpenResult[] openResult =
                    new TimingNodeTypes.OpenResult[1];
            Thread openCaller = new Thread(
                    () -> openResult[0] = node.open(new LocationId(24)),
                    "timing-node-open-caller");
            openCaller.start();
            openCaller.join(250L);
            assertFalse(
                    "open() must not wait for persisted-data recovery",
                    openCaller.isAlive());

            assertEquals(TimingNodeTypes.OpenResult.OPENED, openResult[0]);
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            persistence.releaseLoad.countDown();
            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            persistence.releaseLoad.countDown();
            activationCaller.join(1000L);
            deactivate(node);
        }
    }

    @Test
    public void failedPersistedDataLoadDoesNotReplaceFunctionalState() {
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                new FailingLoadPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);

        activate(node);
        try {
            LogBookRecoveryStatus recovery =
                    node.logBook().process(LogBookQueries.recoveryStatus());
            assertTrue(recovery.failed());
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());
            assertNull(node.locationId());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void openAppliesRequestedLocationAtomically() {
        TimingNode node = node(new NodeId("A"));
        LocationId openLocation = new LocationId(24);

        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(openLocation));

            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(openLocation, node.locationId());

            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.close());

            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());
            assertEquals(openLocation, node.locationId());
        } finally {
            deactivate(node);
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

        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.close());

            assertEquals(
                    2,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
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
            deactivate(node);
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

        activate(node);
        try {
            node.open(new LocationId(24));
            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());

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
            deactivate(node);
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

        activate(node);
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

            assertEquals(
                    2,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(2, persistence.records.size());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void failedOpenHistoryKeepsNodeOpen() {
        FailingPersistence persistence = new FailingPersistence();
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);
        List<TimingData> committedEvents = new ArrayList<TimingData>();
        node.logBook().newDataEntryEvent().subscribe(committedEvents::add);

        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());

            assertEquals(
                    0,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertTrue(committedEvents.isEmpty());
        } finally {
            deactivate(node);
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

        activate(node);
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
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.close());
            assertEquals(TimingNodeTypes.CloseResult.ALREADY_CLOSED, node.close());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void lifecycleLogBookCommandsDoNotOwnTimingNodeStatus() {
        TimingNode node = node(new NodeId("A"));

        activate(node);
        try {
            node.logBook().process(
                    LogBookCommands.commitNodeOpen(new LocationId(24)));
            assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
            assertNull(node.locationId());

            node.logBook().process(
                    LogBookCommands.commitNodeClose(new LocationId(24)));
            assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
            assertNull(node.locationId());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void openDoesNotWaitBehindBlockedLogBookAndHistoryCommitsLater()
            throws Exception {
        SerialLane executor = newSerialLane(2, "timing-node-test");
        TimingNode node = node(
                new NodeId("A"),
                executor,
                25L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        activate(node);
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());

            releaseBlocker.countDown();

            List<TimingData> committed =
                    node.logBook().process(
                            LogBookQueries.timingDataRange(1L, 1));
            assertEquals(1, committed.size());
            assertTrue(committed.get(0) instanceof TimingData.NodeOpen);
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
        } finally {
            releaseBlocker.countDown();
            deactivate(node);
        }
    }

    @Test
    public void rejectedOpenHistoryDoesNotRejectFunctionalOpen()
            throws Exception {
        SerialLane executor = newSerialLane(1, "timing-node-full-logbook-test");
        TimingNode node = node(
                new NodeId("A"),
                executor,
                1000L);
        CountDownLatch startupCompleted = new CountDownLatch(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch fillerCommitted = new CountDownLatch(1);
        node.logBook().recoveryStatusChangedEvent().subscribe(status -> startupCompleted.countDown());
        node.logBook().newDataEntryEvent().subscribe(data -> fillerCommitted.countDown());

        activate(node);
        try {
            assertTrue(startupCompleted.await(1, TimeUnit.SECONDS));

            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            node.logBook().execute(
                    LogBookCommands.commitNodeOpen(new LocationId(99)));

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());

            releaseBlocker.countDown();
            assertTrue(fillerCommitted.await(1, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            releaseBlocker.countDown();
            deactivate(node);
        }
    }

    @Test
    public void delayedLifecycleWriteDoesNotDelayFunctionalOpen()
            throws Exception {
        SerialLane executor = newSerialLane(4, "timing-node-test-hook");
        TimingNode node = TimingNodeFixture.createWithDataLane(
                new NodeId("A"),
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now,
                executor,
                25L,
                1000L);

        activate(node);
        try {
            ((LogBookTestControl) node.logBook()).armNextWriteFailure(
                    LogBookTestControl.WriteFailure.TIMEOUT);

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());

            List<TimingData> committed =
                    node.logBook().process(
                            LogBookQueries.timingDataRange(1L, 1));
            assertEquals(1, committed.size());
            assertTrue(committed.get(0) instanceof TimingData.NodeOpen);
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void concurrentOpenAndCloseQueueHistoryInFunctionalOrder()
            throws Exception {
        SerialLane executor = newSerialLane(4, "timing-node-test");
        TimingNode node = node(
                new NodeId("A"),
                executor,
                1000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        activate(node);
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            final TimingNodeTypes.OpenResult[] openResult =
                    new TimingNodeTypes.OpenResult[1];
            final TimingNodeTypes.CloseResult[] closeResult =
                    new TimingNodeTypes.CloseResult[1];

            Thread openCaller = new Thread(
                    () -> openResult[0] = node.open(new LocationId(24)));
            Thread closeCaller = new Thread(
                    () -> closeResult[0] = node.close());

            openCaller.start();
            openCaller.join(1000L);
            assertFalse(openCaller.isAlive());

            closeCaller.start();
            closeCaller.join(1000L);
            assertFalse(closeCaller.isAlive());

            assertEquals(TimingNodeTypes.OpenResult.OPENED, openResult[0]);
            assertEquals(TimingNodeTypes.CloseResult.CLOSED, closeResult[0]);
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());
            /*
             * OPEN and CLOSE are two LogBook items behind one fixed Data Lane
             * queue worker. The underlying SerialLane therefore contains one
             * worker entry, not one executable entry per LogBook item.
             */
            assertEquals(1, executor.metrics().snapshot().queueDepth());

            releaseBlocker.countDown();

            List<TimingData> committed =
                    node.logBook().process(
                            LogBookQueries.timingDataRange(1L, 2));
            assertEquals(2, committed.size());
            assertTrue(committed.get(0) instanceof TimingData.NodeOpen);
            assertTrue(committed.get(1) instanceof TimingData.NodeClose);
        } finally {
            releaseBlocker.countDown();
            deactivate(node);
        }
    }

    /** Verifies that LogBook.execute returns after lane admission without waiting for command completion. */
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
        activate(node);
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

            assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
            assertNull(node.locationId());
        } finally {
            releaseBlocker.countDown();
            deactivate(node);
        }
    }


    @Test
    public void currentStatusDoesNotWaitBehindQueuedWork() throws Exception {
        SerialLane executor = newSerialLane(3, "timing-node-current-test");
        TimingNode node = node(new NodeId("A"), executor, 25L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        activate(node);
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            node.logBook().execute(
                    LogBookCommands.commitNodeOpen(new LocationId(24)));

            assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());

            try {
                node.logBook().process(
                        LogBookQueries.recoveryStatus(),
                        ReadConsistency.ORDERED);
                fail("expected ordered recovery query timeout behind blocked lane");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("timed out"));
            }

            releaseBlocker.countDown();
            CountDownLatch drained = new CountDownLatch(1);
            executor.execute(drained::countDown);
            assertTrue(drained.await(1, TimeUnit.SECONDS));

            assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
        } finally {
            releaseBlocker.countDown();
            deactivate(node);
        }
    }

    @Test
    public void functionalStateEventDoesNotWaitForHistoryCommit()
            throws Exception {
        SerialLane executor = newSerialLane(2, "timing-node-state-event-test");
        TimingNode node = node(new NodeId("A"), executor, 1000L);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch functionalEvent = new CountDownLatch(1);
        CountDownLatch historyEvent = new CountDownLatch(1);

        node.PROPERTIES.CHANGED.subscribe(
                change -> {
                    if (changedProperty(change, "functionalState")
                            && node.functionalState() == TimingNodeTypes.FunctionalState.OPEN) {
                        functionalEvent.countDown();
                    }
                });
        node.logBook().newDataEntryEvent().subscribe(
                ignored -> historyEvent.countDown());

        activate(node);
        try {
            executor.submit(() -> {
                blockerStarted.countDown();
                releaseBlocker.await();
                return null;
            });
            assertTrue(blockerStarted.await(1L, TimeUnit.SECONDS));

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));

            assertTrue(functionalEvent.await(250L, TimeUnit.MILLISECONDS));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());
            assertEquals(
                    "history must still be blocked on the Data Lane",
                    1L,
                    historyEvent.getCount());

            releaseBlocker.countDown();
            assertTrue(historyEvent.await(1L, TimeUnit.SECONDS));
        } finally {
            releaseBlocker.countDown();
            deactivate(node);
        }
    }

    @Test
    public void currentConsistencyRejectsOrderedOnlyLogBookQuery() {
        TimingNode node = node(new NodeId("A"));

        activate(node);
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
            deactivate(node);
        }
    }

    @Test
    public void orderedRecoveryQueryReadsDirectlyInsideLogBookVisitor() {
        TimingNode node = node(new NodeId("A"));
        final LogBookRecoveryStatus[] orderedStatus =
                new LogBookRecoveryStatus[1];
        final LogBookRecoveryStatus[] currentStatus =
                new LogBookRecoveryStatus[1];

        activate(node);
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
            deactivate(node);
        }
    }

    @Test
    public void statusIsClosedOutsideActivation() {
        TimingNode node = node(new NodeId("A"));

        assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
        assertNull(node.locationId());

        activate(node);
        assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
        deactivate(node);

        assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
        assertNull(node.locationId());
    }

    @Test
    public void invalidSecondActivationDoesNotClearPublishedCurrentStatus() {
        TimingNode node = node(new NodeId("A"));

        activate(node);
        try {
            TimingNodeTypes.FunctionalState functionalBefore =
                    node.functionalState();
            LocationId locationBefore = node.locationId();

            try {
                activate(node);
                fail("expected duplicate activation failure");
            } catch (IllegalStateException expected) {
                // Existing active lifecycle remains authoritative.
            }

            assertEquals(functionalBefore, node.functionalState());
            assertEquals(locationBefore, node.locationId());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void activationFailsWhenPersistedDataLoadCannotBeQueued() {
        SerialLane executor =
                new SerialLane(
                        2,
                        2,
                        "timing-node-failed-current-test",
                        runnable -> {
                            throw new RejectedExecutionException(
                                    "expected worker rejection");
                        });
        TimingNode node = node(new NodeId("A"), executor, 1000L);

        try {
            activate(node);
            fail("expected activation failure");
        } catch (IllegalStateException expected) {
            assertTrue(
                    expected.getMessage()
                            .contains("Persisted TimingData load"));
        } finally {
            deactivate(node);
        }

        assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
        assertNull(node.locationId());
    }

    @Test
    public void commandBeforeStartIsUnavailableWhileStatusIsReadable() {
        TimingNode node = node(new NodeId("A"));

        assertEquals(TimingNodeTypes.FunctionalState.CLOSED, node.functionalState());
        try {
            node.open(new LocationId(24));
            fail("expected operation failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not active"));
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
    private static boolean changedProperty(
            PropertyUpdate update,
            String name) {

        for (Property<?> property : update.properties) {
            if (name.equals(property.name)) {
                return true;
            }
        }
        return false;
    }

}
