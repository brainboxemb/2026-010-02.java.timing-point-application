package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
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

    private TimingNodeDataLane newDataLane(
            int capacity,
            String threadName) {
        ExecutorService worker =
                Executors.newSingleThreadExecutor(
                        runnable -> new Thread(runnable, threadName));
        ownedWorkers.add(worker);
        return new TimingNodeDataLane(
                capacity,
                capacity,
                threadName,
                worker);
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
    public void activationReturnsWhileMainTaskLoadsPersistedData()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(
                        4,
                        "timing-node-load-worker");
        BlockingPersistence persistence =
                new BlockingPersistence(dataLane);
        TimingNode node =
                node(
                        new NodeId("A"),
                        persistence,
                        dataLane,
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
                    "activate() must return while MainTask recovery is running",
                    activationCaller.isAlive());
            assertNull(activationFailure[0]);
            assertEquals(
                    TimingNodeTypes.OperationalState.INIT,
                    node.operationalState());
            assertFalse(
                    "persisted-data recovery must not run on the Data Lane",
                    persistence.executedOnNodeLane);

            persistence.releaseLoad.countDown();

            long deadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(1L);
            while (node.operationalState()
                            == TimingNodeTypes.OperationalState.INIT
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }

            assertEquals(
                    TimingNodeTypes.OperationalState.OK,
                    node.operationalState());
            assertEquals(
                    0,
                    node.logBook().process(
                            LogBookQueries.timingDataCount()).intValue());
        } finally {
            persistence.releaseLoad.countDown();
            activationCaller.join(1000L);
            deactivate(node);
        }
    }

    @Test
    public void deactivationDoesNotWaitForRunningMainTask()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(
                        4,
                        "timing-node-stop-worker");
        UninterruptibleLoadPersistence persistence =
                new UninterruptibleLoadPersistence();
        TimingNode node =
                node(
                        new NodeId("A"),
                        persistence,
                        dataLane,
                        1000L);
        LogBook logBook = (LogBook) node.logBook();
        CountDownLatch initialDataLoaded = new CountDownLatch(1);
        CountDownLatch recoveryPublished = new CountDownLatch(1);
        logBook.INITIAL_DATA_LOADED.subscribe(ignored -> initialDataLoaded.countDown());
        logBook.RECOVERY_STATUS_CHANGED.subscribe(ignored -> recoveryPublished.countDown());

        activate(node);
        assertTrue(
                "persisted-data load did not start",
                persistence.loadStarted.await(
                        1L,
                        TimeUnit.SECONDS));

        Thread deactivationCaller =
                new Thread(
                        () -> deactivate(node),
                        "timing-node-deactivation-caller");
        deactivationCaller.start();
        deactivationCaller.join(250L);

        assertFalse(
                "deactivate() must not wait for the running MainTask",
                deactivationCaller.isAlive());
        assertEquals(
                TimingNodeTypes.OperationalState.INIT,
                node.operationalState());

        persistence.releaseLoad.countDown();
        assertTrue(
                "blocking persistence did not return",
                persistence.loadReturned.await(1L, TimeUnit.SECONDS));
        assertFalse(
                "inactive LogBook must not publish INITIAL_DATA_LOADED",
                initialDataLoaded.await(100L, TimeUnit.MILLISECONDS));
        assertFalse(
                "inactive LogBook must not publish recovery status",
                recoveryPublished.await(100L, TimeUnit.MILLISECONDS));
        assertEquals(
                "cancelled MainTask must not publish a completion state",
                TimingNodeTypes.OperationalState.INIT,
                node.operationalState());
        deactivationCaller.join(1000L);
    }
    @Test
    public void failedPersistedDataLoadDoesNotReplaceFunctionalState() throws Exception {
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                new FailingLoadPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);

        activate(node);
        try {
            awaitOperationalState(
                    node,
                    TimingNodeTypes.OperationalState.OK);
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
    public void openDoesNotWaitForBlockedHistoryAppend()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(2, "timing-node-test");
        BlockingAppendPersistence persistence =
                new BlockingAppendPersistence();
        TimingNode node =
                node(
                        new NodeId("A"),
                        persistence,
                        dataLane,
                        1000L);

        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());
            assertTrue(
                    persistence.appendStarted.await(
                            1L,
                            TimeUnit.SECONDS));

            persistence.releaseAppend.countDown();

            List<TimingData> committed =
                    node.logBook().process(
                            LogBookQueries.timingDataRange(1L, 1));
            assertEquals(1, committed.size());
            assertTrue(committed.get(0) instanceof TimingData.NodeOpen);
        } finally {
            persistence.releaseAppend.countDown();
            deactivate(node);
        }
    }

    @Test
    public void rejectedOpenHistoryDoesNotRejectFunctionalOpen()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(1, "timing-node-full-logbook-test");
        BlockingAppendPersistence persistence =
                new BlockingAppendPersistence();
        TimingNode node =
                node(
                        new NodeId("A"),
                        persistence,
                        dataLane,
                        1000L);
        CountDownLatch committed = new CountDownLatch(2);
        node.logBook().newDataEntryEvent().subscribe(
                ignored -> committed.countDown());

        activate(node);
        try {
            node.logBook().execute(
                    LogBookCommands.commitNodeOpen(new LocationId(99)));
            assertTrue(
                    persistence.appendStarted.await(
                            1L,
                            TimeUnit.SECONDS));

            // Fill the only waiting Data Lane slot while the first append is blocked.
            node.logBook().execute(
                    LogBookCommands.commitNodeClose(new LocationId(99)));

            // This LogBook history command is rejected, but functional state still changes.
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());

            persistence.releaseAppend.countDown();
            assertTrue(
                    "accepted LogBook commands did not drain",
                    committed.await(1L, TimeUnit.SECONDS));
            assertEquals(
                    2,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            persistence.releaseAppend.countDown();
            deactivate(node);
        }
    }

    @Test
    public void delayedLifecycleWriteDoesNotDelayFunctionalOpen()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(4, "timing-node-test-hook");
        TimingNode node = TimingNodeFixture.createWithDataLane(
                new NodeId("A"),
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now,
                dataLane,
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
    public void concurrentOpenAndCloseCommitHistoryInFunctionalOrder()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(4, "timing-node-test");
        BlockingAppendPersistence persistence =
                new BlockingAppendPersistence();
        TimingNode node =
                node(
                        new NodeId("A"),
                        persistence,
                        dataLane,
                        1000L);

        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            assertTrue(
                    persistence.appendStarted.await(
                            1L,
                            TimeUnit.SECONDS));

            assertEquals(
                    TimingNodeTypes.CloseResult.CLOSED,
                    node.close());
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());
            assertEquals(new LocationId(24), node.locationId());

            persistence.releaseAppend.countDown();

            List<TimingData> committed =
                    node.logBook().process(
                            LogBookQueries.timingDataRange(1L, 2));
            assertEquals(2, committed.size());
            assertTrue(committed.get(0) instanceof TimingData.NodeOpen);
            assertTrue(committed.get(1) instanceof TimingData.NodeClose);
        } finally {
            persistence.releaseAppend.countDown();
            deactivate(node);
        }
    }

    @Test
    public void queueReturnsAfterAdmissionWithoutWaitingForExecution()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(2, "timing-node-offer-test");
        BlockingAppendPersistence persistence =
                new BlockingAppendPersistence();
        TimingNode node =
                node(
                        new NodeId("A"),
                        persistence,
                        dataLane,
                        1000L);
        CountDownLatch producerReturned = new CountDownLatch(1);

        activate(node);
        try {
            Thread producer = new Thread(
                    () -> {
                        node.logBook().execute(
                                LogBookCommands.commitNodeOpen(
                                        new LocationId(24)));
                        producerReturned.countDown();
                    });
            producer.start();

            assertTrue(
                    "offer-only producer must not wait for command execution",
                    producerReturned.await(250L, TimeUnit.MILLISECONDS));
            assertTrue(
                    persistence.appendStarted.await(
                            1L,
                            TimeUnit.SECONDS));

            persistence.releaseAppend.countDown();
            producer.join(1000L);
            assertFalse(producer.isAlive());

            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            persistence.releaseAppend.countDown();
            deactivate(node);
        }
    }

    @Test
    public void currentStatusDoesNotWaitBehindQueuedWork() throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(3, "timing-node-current-test");
        BlockingAppendPersistence persistence =
                new BlockingAppendPersistence();
        TimingNode node =
                node(new NodeId("A"), persistence, dataLane, 25L);

        activate(node);
        try {
            node.logBook().execute(
                    LogBookCommands.commitNodeOpen(new LocationId(24)));
            assertTrue(
                    persistence.appendStarted.await(
                            1L,
                            TimeUnit.SECONDS));

            LogBookRecoveryStatus current =
                    node.logBook().process(
                            LogBookQueries.recoveryStatus(),
                            ReadConsistency.CURRENT);
            assertFalse(current.failed());

            try {
                node.logBook().process(
                        LogBookQueries.recoveryStatus(),
                        ReadConsistency.ORDERED);
                fail("expected ordered recovery query timeout behind blocked lane");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("timed out"));
            }

            persistence.releaseAppend.countDown();
            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            persistence.releaseAppend.countDown();
            deactivate(node);
        }
    }

    @Test
    public void functionalStateEventDoesNotWaitForHistoryCommit()
            throws Exception {
        TimingNodeDataLane dataLane =
                newDataLane(2, "timing-node-state-event-test");
        BlockingAppendPersistence persistence =
                new BlockingAppendPersistence();
        TimingNode node =
                node(new NodeId("A"), persistence, dataLane, 1000L);
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
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));

            assertTrue(functionalEvent.await(250L, TimeUnit.MILLISECONDS));
            assertTrue(
                    persistence.appendStarted.await(
                            1L,
                            TimeUnit.SECONDS));
            assertEquals(
                    "history must still be waiting for persistence",
                    1L,
                    historyEvent.getCount());

            persistence.releaseAppend.countDown();
            assertTrue(historyEvent.await(1L, TimeUnit.SECONDS));
        } finally {
            persistence.releaseAppend.countDown();
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
    public void recoveryFailureStillAllowsDataLaneActivation() throws Exception {
        TimingNode node = TimingNodeFixture.createDirect(
                new NodeId("A"),
                new FailingLoadPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now);

        activate(node);
        try {
            awaitOperationalState(
                    node,
                    TimingNodeTypes.OperationalState.OK);
            LogBookRecoveryStatus recovery =
                    node.logBook().process(LogBookQueries.recoveryStatus());
            assertTrue(recovery.failed());
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
        } finally {
            deactivate(node);
        }
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

    private static void awaitOperationalState(
            TimingNode node,
            TimingNodeTypes.OperationalState expected)
            throws Exception {

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(1L);
        while (node.operationalState() != expected) {
            if (System.nanoTime() >= deadline) {
                fail(
                        "TimingNode did not reach operational state "
                                + expected
                                + "; current="
                                + node.operationalState());
            }
            Thread.sleep(1L);
        }
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
            TimingNodeDataLane dataLane,
            long timeoutMillis) {
        return TimingNodeFixture.createWithDataLane(
                id,
                new NoOpPersistence(),
                new DefaultTimingDataFactory(),
                TimingNodeTest::now,
                dataLane,
                timeoutMillis);
    }

    private static TimingNode node(
            NodeId id,
            TimingDataPersistence persistence,
            TimingNodeDataLane dataLane,
            long timeoutMillis) {
        return TimingNodeFixture.createWithDataLane(
                id,
                persistence,
                new DefaultTimingDataFactory(),
                TimingNodeTest::now,
                dataLane,
                timeoutMillis);
    }

    private static Instant now() {
        return Instant.parse("2026-10-02T08:00:00Z");
    }

    private static final class UninterruptibleLoadPersistence
            implements TimingDataPersistence {
        private final CountDownLatch loadStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseLoad =
                new CountDownLatch(1);
        private final CountDownLatch loadReturned =
                new CountDownLatch(1);

        @Override
        public LoadResult load() {
            loadStarted.countDown();
            boolean interrupted = false;
            while (releaseLoad.getCount() != 0L) {
                try {
                    releaseLoad.await();
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            loadReturned.countDown();
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // MainTask deactivation test only.
        }
    }
    private static final class BlockingPersistence implements TimingDataPersistence {
        private final TimingNodeDataLane expectedLane;
        private final CountDownLatch loadStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseLoad =
                new CountDownLatch(1);
        private volatile boolean executedOnNodeLane;

        private BlockingPersistence(
                TimingNodeDataLane expectedLane) {
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
            // Lifecycle-recovery test only.
        }
    }

    private static final class BlockingAppendPersistence
            implements TimingDataPersistence {
        private final CountDownLatch appendStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseAppend =
                new CountDownLatch(1);

        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            appendStarted.countDown();
            try {
                releaseAppend.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while blocking test append",
                        ex);
            }
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
