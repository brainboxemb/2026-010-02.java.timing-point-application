package io.github.brainboxemb.eventtiming.timingpoint.domain.node;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookRecoveryStatus;

import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimingNodeRegistrationTest {
    private static final TimingTimestamp EFFECTIVE_TIME =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void commitsAcceptedRegistrationAsAutomaticTimingData() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();
        node.logBook().newDataEntryEvent().subscribe(delivered::add);

        activate(node);
        try {
            node.open(new LocationId(24));

            RegistrationId registrationId = new RegistrationId("1001");
            TimingNodeTypes.RegistrationResult result =
                    addTestAutomaticRegistration(node, 
                            registrationId,
                            EFFECTIVE_TIME,
                            TimingData.AutomaticTimeSource.API);

            assertTrue(result.committed());
            assertTrue(result.timingData() instanceof AutomaticRegistration);
            AutomaticRegistration data =
                    (AutomaticRegistration) result.timingData();
            assertEquals(new NodeId("A"), data.timingNodeId());
            assertEquals(2L, data.sequenceNumber());
            assertEquals(new LocationId(24), data.locationId());
            assertEquals(EFFECTIVE_TIME, data.effectiveTime());
            assertEquals(RECORDED_AT, data.recordedAt());
            assertSame(registrationId, data.registrationId());

            assertEquals(2, store.appended.size());
            assertSame(data, store.appended.get(1));
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertSame(data, delivered.get(1));
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void customRegistrationsRecordWhereTheSuppliedTimeCameFrom() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        activate(node);
        try {
            node.open(new LocationId(24));

            TimingTimestamp nodeTime =
                    new TimingTimestamp(node.timeSource().now());
            AutomaticRegistration fromNodeClock =
                    (AutomaticRegistration) addTestAutomaticRegistration(node, 
                            new RegistrationId("RT-A-0001"),
                            nodeTime,
                            TimingData.AutomaticTimeSource.NODE)
                            .timingData();
            assertEquals(RECORDED_AT, fromNodeClock.effectiveTime());
            assertEquals(RECORDED_AT, fromNodeClock.recordedAt());
            assertEquals(TimingData.TagSource.API, fromNodeClock.tagSource());
            assertEquals(
                    TimingData.AutomaticTimeSource.NODE,
                    fromNodeClock.registrationTimeSource());

            AutomaticRegistration explicit =
                    (AutomaticRegistration) addTestAutomaticRegistration(node, 
                            new RegistrationId("RT-A-0002"),
                            EFFECTIVE_TIME,
                            TimingData.AutomaticTimeSource.API)
                            .timingData();
            assertEquals(EFFECTIVE_TIME, explicit.effectiveTime());
            assertEquals(TimingData.TagSource.API, explicit.tagSource());
            assertEquals(
                    TimingData.AutomaticTimeSource.API,
                    explicit.registrationTimeSource());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void customRegistrationRejectsObservationTimeSource() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {
            node.open(new LocationId(24));

            try {
                addTestAutomaticRegistration(node, 
                        new RegistrationId("RT-A-0003"),
                        EFFECTIVE_TIME,
                        TimingData.AutomaticTimeSource.OBS);
                fail("custom registration must reject OBS time source");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("API or NODE"));
            }
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void registrationAddsNormalizeEffectiveTimeToCentisecond() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {
            node.open(new LocationId(24));

            TimingNodeTypes.RegistrationResult automatic =
                    addTestAutomaticRegistration(node, 
                            new RegistrationId("1001"),
                            TimingTimestamp.parse(
                                    "2026-10-01T12:00:00.129876543Z"),
                            TimingData.AutomaticTimeSource.API);
            TimingNodeTypes.RegistrationResult manual =
                    addManualRegistration(node, 
                            new RegistrationId("1002"),
                            TimingTimestamp.parse(
                                    "2026-10-01T12:00:01.987654321Z"),
                            ManualTimeSource.OPERATOR_ENTERED);

            assertEquals(
                    TimingTimestamp.parse(
                            "2026-10-01T12:00:00.12Z"),
                    automatic.timingData().effectiveTime());
            assertEquals(
                    TimingTimestamp.parse(
                            "2026-10-01T12:00:01.98Z"),
                    manual.timingData().effectiveTime());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void acceptedRegistrationWhileClosedIsRejectedWithoutCommit() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {

            TimingNodeTypes.RegistrationResult rejected =
                    addTestAutomaticRegistration(node, 
                            new RegistrationId("1001"),
                            EFFECTIVE_TIME,
                            TimingData.AutomaticTimeSource.API);

            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertEquals(0, store.attempts);
            assertEquals(0, node.logBook().process(LogBookQueries.timingDataCount()).intValue());

            node.open(new LocationId(24));
            TimingNodeTypes.RegistrationResult committed =
                    addTestAutomaticRegistration(node, 
                            new RegistrationId("1002"),
                            EFFECTIVE_TIME,
                            TimingData.AutomaticTimeSource.API);
            assertEquals(2L, committed.timingData().sequenceNumber());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void commitsManualRegistrationAfterStoreAppend() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {
            assertEquals(TimingNodeTypes.OpenResult.OPENED, node.open(new LocationId(24)));

            TimingNodeTypes.RegistrationResult result = addManualRegistration(node, 
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);

            assertTrue(result.committed());
            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.COMMITTED,
                    result.outcome());
            assertEquals(2, store.appended.size());
            assertSame(result.timingData(), store.appended.get(1));

            TimingData[] visited = new TimingData[1];
            int totalCount = node.logBook().process(
                    LogBookQueries.visitTimingDataRange(
                            2L,
                            1,
                            data -> visited[0] = data));
            assertEquals(2, totalCount);
            assertSame(result.timingData(), visited[0]);

            ManualRegistration data =
                    (ManualRegistration) result.timingData();
            assertEquals(new NodeId("A"), data.timingNodeId());
            assertEquals(2L, data.sequenceNumber());
            assertEquals(new LocationId(24), data.locationId());
            assertEquals(EFFECTIVE_TIME, data.effectiveTime());
            assertEquals(RECORDED_AT, data.recordedAt());
            assertEquals(new RegistrationId("1001"), data.registrationId());
            assertEquals(
                    ManualTimeSource.OPERATOR_ENTERED,
                    data.timeSource());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void assignsSequenceOnlyFromCommittedLogBookState() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {
            node.open(new LocationId(24));

            TimingNodeTypes.RegistrationResult first = addTestAutomaticRegistration(node, 
                            new RegistrationId("1001"),
                            EFFECTIVE_TIME,
                            TimingData.AutomaticTimeSource.API);
            TimingNodeTypes.RegistrationResult second = addManualRegistration(node, 
                    new RegistrationId("1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.AUTOMATIC);

            assertEquals(2L, first.timingData().sequenceNumber());
            assertEquals(3L, second.timingData().sequenceNumber());
            assertEquals(3, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void closedNodeRejectsWithoutAllocatingOrPersisting() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {

            TimingNodeTypes.RegistrationResult rejected = addManualRegistration(node, 
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);

            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertEquals(0, store.appended.size());
            assertEquals(0, node.logBook().process(LogBookQueries.timingDataCount()).intValue());

            node.open(new LocationId(24));
            TimingNodeTypes.RegistrationResult committed = addManualRegistration(node, 
                    new RegistrationId("1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);
            assertEquals(2L, committed.timingData().sequenceNumber());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void appendFailureLeavesLogBookUnchangedAndBlocksLaterCommit() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {
            node.open(new LocationId(24));
            store.failNext = true;

            try {
                addManualRegistration(node, 
                        new RegistrationId("1001"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED);
                fail("expected persistence failure");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(2, store.attempts);

            try {
                addManualRegistration(node, 
                        new RegistrationId("1002"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED);
                fail("expected blocked commit");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            assertEquals(2, store.attempts);
            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void emitsOnlyAfterSuccessfulCommit() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();
        Consumer<TimingData> listener = delivered::add;

        assertTrue(node.logBook().newDataEntryEvent().subscribe(listener));

        activate(node);
        try {

            TimingNodeTypes.RegistrationResult rejected = addManualRegistration(node, 
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);
            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertTrue(delivered.isEmpty());

            node.open(new LocationId(24));
            TimingNodeTypes.RegistrationResult committed = addManualRegistration(node, 
                    new RegistrationId("1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);

            assertEquals(2, store.appended.size());
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(2, delivered.size());
            assertSame(committed.timingData(), delivered.get(1));

        } finally {
            deactivate(node);
        }
    }

    @Test
    public void appendFailureDoesNotEmitTimingData() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();
        node.logBook().newDataEntryEvent().subscribe(delivered::add);

        activate(node);
        try {
            node.open(new LocationId(24));
            delivered.clear();
            store.failNext = true;

            try {
                addManualRegistration(node, 
                        new RegistrationId("1001"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED);
                fail("expected persistence failure");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            assertTrue(delivered.isEmpty());
            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void listenerRuntimeFailureDoesNotRollbackCommitOrBlockLaterListener() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();

        node.logBook().newDataEntryEvent().subscribe(data -> {
            throw new IllegalStateException("expected listener failure");
        });
        node.logBook().newDataEntryEvent().subscribe(delivered::add);

        activate(node);
        try {
            node.open(new LocationId(24));
            delivered.clear();

            TimingNodeTypes.RegistrationResult committed = addManualRegistration(node, 
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.AUTOMATIC);

            assertTrue(committed.committed());
            assertEquals(2, store.appended.size());
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(1, delivered.size());
            assertSame(committed.timingData(), delivered.get(0));
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void automaticRevokeCommitsWithoutLookingUpAnAddRecord() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {
            TimingNodeTypes.RegistrationResult result =
                    node.logBook().process(
                            LogBookCommands.revokeAutomaticRegistration(
                                    new LocationId(24),
                                    new RegistrationId("1001"),
                                    EFFECTIVE_TIME));

            AutomaticRegistration data =
                    (AutomaticRegistration) result.timingData();
            assertEquals(1L, data.sequenceNumber());
            assertEquals(new LocationId(24), data.locationId());
            assertEquals(EFFECTIVE_TIME, data.effectiveTime());
            assertEquals(
                    TimingData.RegistrationAction.REV,
                    data.action());
            assertEquals(1, store.appended.size());
            assertSame(data, store.appended.get(0));
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void manualRevokePreservesOriginalTimeSourceWithoutHistoryLookup() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        activate(node);
        try {
            TimingNodeTypes.RegistrationResult result =
                    node.logBook().process(
                            LogBookCommands.revokeManualRegistration(
                                    new LocationId(24),
                                    new RegistrationId("1001"),
                                    EFFECTIVE_TIME,
                                    ManualTimeSource.OPERATOR_ENTERED));

            ManualRegistration data =
                    (ManualRegistration) result.timingData();
            assertEquals(
                    TimingData.RegistrationAction.REV,
                    data.action());
            assertEquals(
                    ManualTimeSource.OPERATOR_ENTERED,
                    data.timeSource());
            assertEquals(EFFECTIVE_TIME, data.effectiveTime());
            assertEquals(new LocationId(24), data.locationId());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupLoadDoesNotReplayNewTimingDataEvent() {
        RecordingStore store = new RecordingStore();
        store.loaded.add(loadedData(1L, 11));
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();
        node.logBook().newDataEntryEvent().subscribe(delivered::add);

        activate(node);
        try {
            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertTrue(delivered.isEmpty());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupLoadsCommittedLogBookAndContinuesSequence() {
        RecordingStore store = new RecordingStore();
        store.loaded.add(loadedData(1L, 11));
        store.loaded.add(loadedData(2L, 12));
        TimingNode node = node(store);

        activate(node);
        try {
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());
            assertFalse(node.locationId() != null);
            assertFalse(
                    node.logBook().process(LogBookQueries.recoveryStatus())
                            .repairedIncompleteTail());
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            TimingNodeTypes.RegistrationResult committed = addManualRegistration(node, 
                    new RegistrationId("1003"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.AUTOMATIC);

            assertEquals(4L, committed.timingData().sequenceNumber());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupReportsRepairedIncompleteTailInRecoveryStatus() {
        RecordingStore store = new RecordingStore();
        store.loaded.add(loadedData(1L, 11));
        store.repairedIncompleteTail = true;
        TimingNode node = node(store);

        activate(node);
        try {
            assertTrue(
                    node.logBook().process(LogBookQueries.recoveryStatus())
                            .repairedIncompleteTail());
            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupLoadFailureDoesNotReplaceFunctionalState() {
        RecordingStore store = new RecordingStore();
        store.failLoad = true;
        TimingNode node = node(store);

        activate(node);
        try {
            LogBookRecoveryStatus recovery =
                    node.logBook().process(LogBookQueries.recoveryStatus());
            assertTrue(recovery.failed());

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

            try {
                node.logBook().process(LogBookQueries.timingDataCount());
                fail("expected failed LogBook to reject ordered query");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            try {
                activate(node);
                fail("expected running node not to restart");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("already active"));
            }
        } finally {
            deactivate(node);
        }
    }

    private static TimingNodeTypes.RegistrationResult addTestAutomaticRegistration(
            TimingNode node,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.AutomaticTimeSource registrationTimeSource) {

        Supplier<TimingNodeTypes.RegistrationResult> addRegistration = () -> {
            TimingNodeTypes.RegistrationResult result;
            if (node.functionalState() != TimingNodeTypes.FunctionalState.OPEN) {
                result = TimingNodeTypes.RegistrationResult.nodeNotOpen();
            } else {
                result = node.logBook().process(
                        LogBookCommands.commitAutomaticRegistration(
                                node.locationId(),
                                registrationId,
                                effectiveTime,
                                TimingData.TagSource.API,
                                registrationTimeSource));
            }
            return result;
        };
        return node.processLocked(addRegistration);
    }

    private static TimingNodeTypes.RegistrationResult addManualRegistration(
            TimingNode node,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource) {

        Supplier<TimingNodeTypes.RegistrationResult> addRegistration = () -> {
            TimingNodeTypes.RegistrationResult result;
            if (node.functionalState() != TimingNodeTypes.FunctionalState.OPEN) {
                result = TimingNodeTypes.RegistrationResult.nodeNotOpen();
            } else {
                result = node.logBook().process(
                        LogBookCommands.commitManualRegistration(
                                node.locationId(),
                                registrationId,
                                effectiveTime,
                                registrationTimeSource));
            }
            return result;
        };
        return node.processLocked(addRegistration);
    }

    private static TimingData loadedData(long sequence, int locationId) {
        return new DefaultTimingDataFactory().createManualRegistration(
                new Context(
                        new NodeId("A"),
                        sequence,
                        new LocationId(locationId),
                        EFFECTIVE_TIME,
                        RECORDED_AT),
                new RegistrationId("loaded-" + sequence),
                ManualTimeSource.AUTOMATIC);
    }

    private static TimingNode node(RecordingStore store) {
        TimeSource timeSource = () -> RECORDED_AT.instant();
        return TimingNodeFixture.createDirect(
                new NodeId("A"),
                store,
                new DefaultTimingDataFactory(),
                timeSource);
    }

    private static final class RecordingStore implements TimingDataPersistence {
        private final List<TimingData> appended = new ArrayList<>();
        private final List<TimingData> loaded = new ArrayList<>();
        private int attempts;
        private boolean failNext;
        private boolean failLoad;
        private boolean repairedIncompleteTail;

        @Override
        public LoadResult load() throws PersistenceException {
            if (failLoad) {
                throw new PersistenceException("expected persisted-data load failure");
            }
            return new LoadResult(loaded, repairedIncompleteTail);
        }

        @Override
        public void append(TimingData data) throws PersistenceException {
            attempts++;
            if (failNext) {
                failNext = false;
                throw new PersistenceException("expected test failure");
            }
            appended.add(data);
        }
    }
}
