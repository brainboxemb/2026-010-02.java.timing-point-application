package io.github.brainboxemb.eventtiming.timingpoint.domain.node;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import io.github.brainboxemb.eventtiming.timingpoint.application.CommandResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookStatus;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
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
        List<TimingRecord> delivered = new ArrayList<>();
        node.NEW_DATA_ENTRY.subscribe(delivered::add);

        activate(node);
        try {
            node.open(new LocationId(24));

            RegistrationId registrationId = new RegistrationId("N1001");
            CommandResult result =
                    addTestAutomaticRegistration(node,
                            registrationId,
                            EFFECTIVE_TIME,
                            TimingRecord.AutomaticTimeSource.API);

            assertTrue(result.committed());
            TimingRecord data = result.timingRecord();
            assertEquals(TimingRecord.Kind.AUTOMATIC_REGISTRATION, data.kind());
            assertEquals('A', data.nodeId());
            assertEquals(2L, data.sequenceNumber());
            assertEquals(24, data.locationId());
            assertEquals(EFFECTIVE_TIME.instant().toEpochMilli(), data.effectiveTimeMillis());
            assertEquals(RECORDED_AT.instant().toEpochMilli(), data.recordedAtMillis());
            assertEquals(registrationId.packedValue(), data.registrationId());

            assertEquals(2, store.appended.size());
            assertEquals(data, store.appended.get(1));
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(data, delivered.get(1));
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
            TimingRecord fromNodeClock = addTestAutomaticRegistration(node,
                            new RegistrationId("A0001"),
                            nodeTime,
                            TimingRecord.AutomaticTimeSource.NODE)
                            .timingRecord();
            assertEquals(RECORDED_AT.instant().toEpochMilli(), fromNodeClock.effectiveTimeMillis());
            assertEquals(RECORDED_AT.instant().toEpochMilli(), fromNodeClock.recordedAtMillis());
            assertEquals(TimingRecord.TagSource.API, fromNodeClock.tagSource());
            assertEquals(
                    TimingRecord.AutomaticTimeSource.NODE,
                    fromNodeClock.automaticTimeSource());

            TimingRecord explicit = addTestAutomaticRegistration(node,
                            new RegistrationId("A0002"),
                            EFFECTIVE_TIME,
                            TimingRecord.AutomaticTimeSource.API)
                            .timingRecord();
            assertEquals(EFFECTIVE_TIME.instant().toEpochMilli(), explicit.effectiveTimeMillis());
            assertEquals(TimingRecord.TagSource.API, explicit.tagSource());
            assertEquals(
                    TimingRecord.AutomaticTimeSource.API,
                    explicit.automaticTimeSource());
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
                        new RegistrationId("A0003"),
                        EFFECTIVE_TIME,
                        TimingRecord.AutomaticTimeSource.OBS);
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

            CommandResult automatic =
                    addTestAutomaticRegistration(node,
                            new RegistrationId("N1001"),
                            TimingTimestamp.parse(
                                    "2026-10-01T12:00:00.129876543Z"),
                            TimingRecord.AutomaticTimeSource.API);
            CommandResult manual =
                    addManualRegistration(node,
                            new RegistrationId("N1002"),
                            TimingTimestamp.parse(
                                    "2026-10-01T12:00:01.987654321Z"),
                            ManualTimeSource.OPERATOR_ENTERED);

            assertEquals(
                    TimingTimestamp.parse(
                            "2026-10-01T12:00:00.12Z").instant().toEpochMilli(),
                    automatic.timingRecord().effectiveTimeMillis());
            assertEquals(
                    TimingTimestamp.parse(
                            "2026-10-01T12:00:01.98Z").instant().toEpochMilli(),
                    manual.timingRecord().effectiveTimeMillis());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void recordCreationTimePreservesMilliseconds() {
        RecordingStore store = new RecordingStore();
        Instant recordedAt = Instant.parse("2026-10-01T12:00:01.987Z");
        TimingNode node = node(store, () -> recordedAt);

        activate(node);
        try {
            node.open(new LocationId(24));
            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());

            TimingRecord opened = store.appended.get(0);
            assertEquals(recordedAt.toEpochMilli(), opened.recordedAtMillis());
            assertEquals(
                    Instant.parse("2026-10-01T12:00:01.980Z").toEpochMilli(),
                    opened.effectiveTimeMillis());
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

            CommandResult rejected =
                    addTestAutomaticRegistration(node,
                            new RegistrationId("N1001"),
                            EFFECTIVE_TIME,
                            TimingRecord.AutomaticTimeSource.API);

            assertEquals(
                    CommandResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertEquals(0, store.attempts);
            assertEquals(0, node.logBook().process(LogBookQueries.timingDataCount()).intValue());

            node.open(new LocationId(24));
            CommandResult committed =
                    addTestAutomaticRegistration(node,
                            new RegistrationId("N1002"),
                            EFFECTIVE_TIME,
                            TimingRecord.AutomaticTimeSource.API);
            assertEquals(2L, committed.timingRecord().sequenceNumber());
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

            CommandResult result = addManualRegistration(node,
                    new RegistrationId("N1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);

            assertTrue(result.committed());
            assertEquals(
                    CommandResult.Outcome.COMMITTED,
                    result.outcome());
            assertEquals(2, store.appended.size());
            assertEquals(result.timingRecord(), store.appended.get(1));

            TimingRecord[] visited = new TimingRecord[1];
            int totalCount = node.logBook().process(
                    LogBookQueries.visitTimingRecordRange(
                            2L,
                            1,
                            data -> visited[0] = data));
            assertEquals(2, totalCount);
            assertEquals(result.timingRecord(), visited[0]);

            TimingRecord data = result.timingRecord();
            assertEquals(TimingRecord.Kind.MANUAL_REGISTRATION, data.kind());
            assertEquals('A', data.nodeId());
            assertEquals(2L, data.sequenceNumber());
            assertEquals(24, data.locationId());
            assertEquals(EFFECTIVE_TIME.instant().toEpochMilli(), data.effectiveTimeMillis());
            assertEquals(RECORDED_AT.instant().toEpochMilli(), data.recordedAtMillis());
            assertEquals(new RegistrationId("N1001").packedValue(), data.registrationId());
            assertEquals(
                    ManualTimeSource.OPERATOR_ENTERED,
                    data.manualTimeSource());
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

            CommandResult first = addTestAutomaticRegistration(node,
                            new RegistrationId("N1001"),
                            EFFECTIVE_TIME,
                            TimingRecord.AutomaticTimeSource.API);
            CommandResult second = addManualRegistration(node,
                    new RegistrationId("N1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.AUTOMATIC);

            assertEquals(2L, first.timingRecord().sequenceNumber());
            assertEquals(3L, second.timingRecord().sequenceNumber());
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

            CommandResult rejected = addManualRegistration(node,
                    new RegistrationId("N1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);

            assertEquals(
                    CommandResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertEquals(0, store.appended.size());
            assertEquals(0, node.logBook().process(LogBookQueries.timingDataCount()).intValue());

            node.open(new LocationId(24));
            CommandResult committed = addManualRegistration(node,
                    new RegistrationId("N1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);
            assertEquals(2L, committed.timingRecord().sequenceNumber());
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
            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            store.failNext = true;

            try {
                addManualRegistration(node,
                        new RegistrationId("N1001"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED);
                fail("expected persistence failure");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("failed"));
            }

            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(2, store.attempts);

            try {
                addManualRegistration(node,
                        new RegistrationId("N1002"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED);
                fail("expected blocked commit");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("failed"));
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
        List<TimingRecord> delivered = new ArrayList<>();
        Consumer<TimingRecord> listener = delivered::add;

        assertTrue(node.NEW_DATA_ENTRY.subscribe(listener));

        activate(node);
        try {

            CommandResult rejected = addManualRegistration(node,
                    new RegistrationId("N1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);
            assertEquals(
                    CommandResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertTrue(delivered.isEmpty());

            node.open(new LocationId(24));
            CommandResult committed = addManualRegistration(node,
                    new RegistrationId("N1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED);

            assertEquals(2, store.appended.size());
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(2, delivered.size());
            assertEquals(committed.timingRecord(), delivered.get(1));

        } finally {
            deactivate(node);
        }
    }

    @Test
    public void appendFailureDoesNotEmitTimingData() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        List<TimingRecord> delivered = new ArrayList<>();
        node.NEW_DATA_ENTRY.subscribe(delivered::add);

        activate(node);
        try {
            node.open(new LocationId(24));
            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            delivered.clear();
            store.failNext = true;

            try {
                addManualRegistration(node,
                        new RegistrationId("N1001"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED);
                fail("expected persistence failure");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("failed"));
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
        List<TimingRecord> delivered = new ArrayList<>();

        node.NEW_DATA_ENTRY.subscribe(data -> {
            throw new IllegalStateException("expected listener failure");
        });
        node.NEW_DATA_ENTRY.subscribe(delivered::add);

        activate(node);
        try {
            node.open(new LocationId(24));
            assertEquals(
                    1,
                    node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            delivered.clear();

            CommandResult committed = addManualRegistration(node,
                    new RegistrationId("N1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.AUTOMATIC);

            assertTrue(committed.committed());
            assertEquals(2, store.appended.size());
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertEquals(1, delivered.size());
            assertEquals(committed.timingRecord(), delivered.get(0));
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
            TimingRecord data = node.logBook().process(
                                    LogBookCommands.revokeAutomaticRegistration(
                                            new LocationId(24),
                                            new RegistrationId("N1001"),
                                            EFFECTIVE_TIME.instant().toEpochMilli()));
            assertEquals(1L, data.sequenceNumber());
            assertEquals(24, data.locationId());
            assertEquals(EFFECTIVE_TIME.instant().toEpochMilli(), data.effectiveTimeMillis());
            assertEquals(
                    TimingRecord.Action.REV,
                    data.action());
            assertEquals(1, store.appended.size());
            assertEquals(data, store.appended.get(0));
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
            TimingRecord data = node.logBook().process(
                                    LogBookCommands.revokeManualRegistration(
                                            new LocationId(24),
                                            new RegistrationId("N1001"),
                                            EFFECTIVE_TIME.instant().toEpochMilli(),
                                            ManualTimeSource.OPERATOR_ENTERED));
            assertEquals(
                    TimingRecord.Action.REV,
                    data.action());
            assertEquals(
                    ManualTimeSource.OPERATOR_ENTERED,
                    data.manualTimeSource());
            assertEquals(EFFECTIVE_TIME.instant().toEpochMilli(), data.effectiveTimeMillis());
            assertEquals(24, data.locationId());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupLoadDoesNotReplayNewTimingDataEvent() {
        RecordingStore store = new RecordingStore();
        store.loaded.add(loadedData(1L, 11));
        TimingNode node = node(store);
        List<TimingRecord> delivered = new ArrayList<>();
        node.NEW_DATA_ENTRY.subscribe(delivered::add);

        activate(node);
        try {
            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
            assertTrue(delivered.isEmpty());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupLoadsCommittedLogBookAndContinuesSequence() throws Exception {
        RecordingStore store = new RecordingStore();
        store.loaded.add(loadedData(1L, 11));
        store.loaded.add(loadedData(2L, 12));
        TimingNode node = node(store);

        activate(node);
        try {
            awaitOperationalState(
                    node,
                    TimingNodeTypes.OperationalState.OK);
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    node.functionalState());
            assertFalse(node.locationId() != null);
            assertEquals(LogBookStatus.LOADED, node.logBook().status());
            assertEquals(2, node.logBook().process(LogBookQueries.timingDataCount()).intValue());

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.open(new LocationId(24)));
            CommandResult committed = addManualRegistration(node,
                    new RegistrationId("N1003"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.AUTOMATIC);

            assertEquals(4L, committed.timingRecord().sequenceNumber());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupLoadsDataAfterRepairingIncompleteTail() throws Exception {
        RecordingStore store = new RecordingStore();
        store.loaded.add(loadedData(1L, 11));
        store.repairedIncompleteTail = true;
        TimingNode node = node(store);

        activate(node);
        try {
            awaitOperationalState(
                    node,
                    TimingNodeTypes.OperationalState.OK);
            assertEquals(LogBookStatus.LOADED, node.logBook().status());
            assertEquals(1, node.logBook().process(LogBookQueries.timingDataCount()).intValue());
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void startupLoadFailureDoesNotReplaceFunctionalState() throws Exception {
        RecordingStore store = new RecordingStore();
        store.failLoad = true;
        TimingNode node = node(store);

        activate(node);
        try {
            awaitOperationalState(
                    node,
                    TimingNodeTypes.OperationalState.OK);
            assertEquals(LogBookStatus.ERROR, node.logBook().status());

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
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("failed"));
            }

            try {
                activate(node);
                fail("expected running node not to restart");
            } catch (IllegalStateException expected) {
                // Lifecycle infrastructure rejects a second activation.
            }
        } finally {
            deactivate(node);
        }
    }

    private static CommandResult addTestAutomaticRegistration(
            TimingNode node,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingRecord.AutomaticTimeSource registrationTimeSource) {

        failIfInvalidArgument(
                registrationTimeSource == TimingRecord.AutomaticTimeSource.API
                        || registrationTimeSource == TimingRecord.AutomaticTimeSource.NODE,
                "test automatic registration time source must be API or NODE");

        Supplier<CommandResult> addRegistration = () -> {
            CommandResult result;
            if (node.functionalState() != TimingNodeTypes.FunctionalState.OPEN) {
                result = CommandResult.nodeNotOpen();
            } else {
                TimingRecord timingRecord =
                        node.logBook().process(
                                LogBookCommands.commitAutomaticRegistration(
                                        node.locationId(),
                                        registrationId,
                                        effectiveTime.instant().toEpochMilli(),
                                        TimingRecord.TagSource.API,
                                        registrationTimeSource));
                result = CommandResult.committed(timingRecord);
            }
            return result;
        };
        return node.processLocked(addRegistration);
    }

    private static CommandResult addManualRegistration(
            TimingNode node,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource) {

        Supplier<CommandResult> addRegistration = () -> {
            CommandResult result;
            if (node.functionalState() != TimingNodeTypes.FunctionalState.OPEN) {
                result = CommandResult.nodeNotOpen();
            } else {
                TimingRecord timingRecord =
                        node.logBook().process(
                                LogBookCommands.commitManualRegistration(
                                        node.locationId(),
                                        registrationId,
                                        effectiveTime.instant().toEpochMilli(),
                                        registrationTimeSource));
                result = CommandResult.committed(timingRecord);
            }
            return result;
        };
        return node.processLocked(addRegistration);
    }

    private static TimingRecord loadedData(long sequence, int locationId) {
        return TimingRecord.createManualRegistration(
                'A',
                sequence,
                locationId,
                EFFECTIVE_TIME.instant().toEpochMilli(),
                RECORDED_AT.instant().toEpochMilli(),
                new RegistrationId('L', (int) sequence).packedValue(),
                TimingRecord.Action.ADD,
                ManualTimeSource.AUTOMATIC);
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

    private static TimingNode node(RecordingStore store) {
        return node(store, () -> RECORDED_AT.instant());
    }

    private static TimingNode node(RecordingStore store, TimeSource timeSource) {
        return TimingNodeFixture.createDirect(new NodeId("A"), store, timeSource);
    }

    private static final class RecordingStore implements TimingDataPersistence {
        private final List<TimingRecord> appended = new ArrayList<>();
        private final List<TimingRecord> loaded = new ArrayList<>();
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
        public void append(TimingRecord record) throws PersistenceException {
            attempts++;
            if (failNext) {
                failNext = false;
                throw new PersistenceException("expected test failure");
            }
            appended.add(record);
        }
    }
}
