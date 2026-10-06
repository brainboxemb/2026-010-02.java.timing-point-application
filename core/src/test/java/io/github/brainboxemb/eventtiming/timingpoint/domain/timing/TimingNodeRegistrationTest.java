package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

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
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.Test;

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
        node.timingDataCommittedEvent().subscribe(delivered::add);

        node.activate();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            RegistrationId registrationId = new RegistrationId("1001");
            TimingNodeTypes.RegistrationResult result =
                    node.invoke(TimingNodeCommands.addAutomaticRegistration(registrationId, EFFECTIVE_TIME));

            assertTrue(result.committed());
            assertTrue(result.timingData() instanceof AutomaticRegistration);
            AutomaticRegistration data =
                    (AutomaticRegistration) result.timingData();
            assertEquals(new NodeId("TN-01"), data.timingNodeId());
            assertEquals(1L, data.sequenceNumber());
            assertEquals(new LocationId(24), data.locationId());
            assertEquals(EFFECTIVE_TIME, data.effectiveTime());
            assertEquals(RECORDED_AT, data.recordedAt());
            assertSame(registrationId, data.registrationId());

            assertEquals(1, store.appended.size());
            assertSame(data, store.appended.get(0));
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertSame(data, delivered.get(0));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void acceptedRegistrationWhileClosedIsRejectedWithoutCommit() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        node.activate();
        try {

            TimingNodeTypes.RegistrationResult rejected =
                    node.invoke(TimingNodeCommands.addAutomaticRegistration(
                            new RegistrationId("1001"),
                            EFFECTIVE_TIME));

            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertEquals(0, store.attempts);
            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());

            node.invoke(TimingNodeCommands.open(new LocationId(24)));
            TimingNodeTypes.RegistrationResult committed =
                    node.invoke(TimingNodeCommands.addAutomaticRegistration(
                            new RegistrationId("1002"),
                            EFFECTIVE_TIME));
            assertEquals(1L, committed.timingData().sequenceNumber());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void commitsManualRegistrationAfterStoreAppend() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        node.activate();
        try {
            assertEquals(TimingNodeTypes.OpenResult.OPENED, node.invoke(TimingNodeCommands.open(new LocationId(24))));

            TimingNodeTypes.RegistrationResult result = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED));

            assertTrue(result.committed());
            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.COMMITTED,
                    result.outcome());
            assertEquals(1, store.appended.size());
            assertSame(result.timingData(), store.appended.get(0));

            TimingData[] visited = new TimingData[1];
            int totalCount = node.query(
                    TimingNodeQueries.visitTimingDataRange(
                            1L,
                            1,
                            data -> visited[0] = data));
            assertEquals(1, totalCount);
            assertSame(result.timingData(), visited[0]);

            ManualRegistration data =
                    (ManualRegistration) result.timingData();
            assertEquals(new NodeId("TN-01"), data.timingNodeId());
            assertEquals(1L, data.sequenceNumber());
            assertEquals(new LocationId(24), data.locationId());
            assertEquals(EFFECTIVE_TIME, data.effectiveTime());
            assertEquals(RECORDED_AT, data.recordedAt());
            assertEquals(new RegistrationId("1001"), data.registrationId());
            assertEquals(
                    ManualTimeSource.OPERATOR_ENTERED,
                    data.timeSource());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void assignsSequenceOnlyFromCommittedLogBookState() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        node.activate();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            TimingNodeTypes.RegistrationResult first = node.invoke(TimingNodeCommands.addAutomaticRegistration(
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME));
            TimingNodeTypes.RegistrationResult second = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.SYSTEM_ASSIGNED));

            assertEquals(1L, first.timingData().sequenceNumber());
            assertEquals(2L, second.timingData().sequenceNumber());
            assertEquals(2, node.query(TimingNodeQueries.timingDataCount()).intValue());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void closedNodeRejectsWithoutAllocatingOrPersisting() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);

        node.activate();
        try {

            TimingNodeTypes.RegistrationResult rejected = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED));

            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertEquals(0, store.appended.size());
            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());

            node.invoke(TimingNodeCommands.open(new LocationId(24)));
            TimingNodeTypes.RegistrationResult committed = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED));
            assertEquals(1L, committed.timingData().sequenceNumber());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void appendFailureLeavesLogBookUnchangedAndBlocksLaterCommit() {
        RecordingStore store = new RecordingStore();
        store.failNext = true;
        TimingNode node = node(store);

        node.activate();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            try {
                node.invoke(TimingNodeCommands.commitManualRegistration(
                        new RegistrationId("1001"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED));
                fail("expected persistence failure");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertEquals(1, store.attempts);

            try {
                node.invoke(TimingNodeCommands.commitManualRegistration(
                        new RegistrationId("1002"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED));
                fail("expected blocked commit");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            assertEquals(1, store.attempts);
            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void emitsOnlyAfterSuccessfulCommit() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();
        Consumer<TimingData> listener = delivered::add;

        assertTrue(node.timingDataCommittedEvent().subscribe(listener));

        node.activate();
        try {

            TimingNodeTypes.RegistrationResult rejected = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED));
            assertEquals(
                    TimingNodeTypes.RegistrationResult.Outcome.NODE_NOT_OPEN,
                    rejected.outcome());
            assertTrue(delivered.isEmpty());

            node.invoke(TimingNodeCommands.open(new LocationId(24)));
            TimingNodeTypes.RegistrationResult committed = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1002"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.OPERATOR_ENTERED));

            assertEquals(1, store.appended.size());
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertEquals(1, delivered.size());
            assertSame(committed.timingData(), delivered.get(0));

        } finally {
            node.deactivate();
        }
    }

    @Test
    public void appendFailureDoesNotEmitTimingData() {
        RecordingStore store = new RecordingStore();
        store.failNext = true;
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();
        node.timingDataCommittedEvent().subscribe(delivered::add);

        node.activate();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            try {
                node.invoke(TimingNodeCommands.commitManualRegistration(
                        new RegistrationId("1001"),
                        EFFECTIVE_TIME,
                        ManualTimeSource.OPERATOR_ENTERED));
                fail("expected persistence failure");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            assertTrue(delivered.isEmpty());
            assertEquals(0, node.query(TimingNodeQueries.timingDataCount()).intValue());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void listenerRuntimeFailureDoesNotRollbackCommitOrBlockLaterListener() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();

        node.timingDataCommittedEvent().subscribe(data -> {
            throw new IllegalStateException("expected listener failure");
        });
        node.timingDataCommittedEvent().subscribe(delivered::add);

        node.activate();
        try {
            node.invoke(TimingNodeCommands.open(new LocationId(24)));

            TimingNodeTypes.RegistrationResult committed = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1001"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.SYSTEM_ASSIGNED));

            assertTrue(committed.committed());
            assertEquals(1, store.appended.size());
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertEquals(1, delivered.size());
            assertSame(committed.timingData(), delivered.get(0));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void recoveryDoesNotReplayNewTimingDataEvent() {
        RecordingStore store = new RecordingStore();
        store.loaded.add(recoveredData(1L, 11));
        TimingNode node = node(store);
        List<TimingData> delivered = new ArrayList<>();
        node.timingDataCommittedEvent().subscribe(delivered::add);

        node.activate();
        try {
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
            assertTrue(delivered.isEmpty());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void startupRecoversCommittedLogBookAndContinuesSequence() {
        RecordingStore store = new RecordingStore();
        store.loaded.add(recoveredData(1L, 11));
        store.loaded.add(recoveredData(2L, 12));
        TimingNode node = node(store);

        node.activate();
        try {
            TimingNodeTypes.Status status = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.State.CLOSED, status.state());
            assertFalse(status.hasLocation());
            assertFalse(status.timingDataTailRecovered());
            assertEquals(2, node.query(TimingNodeQueries.timingDataCount()).intValue());

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    node.invoke(TimingNodeCommands.open(new LocationId(24))));
            TimingNodeTypes.RegistrationResult committed = node.invoke(TimingNodeCommands.commitManualRegistration(
                    new RegistrationId("1003"),
                    EFFECTIVE_TIME,
                    ManualTimeSource.SYSTEM_ASSIGNED));

            assertEquals(3L, committed.timingData().sequenceNumber());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void startupReportsRepairedIncompleteTailInStatus() {
        RecordingStore store = new RecordingStore();
        store.loaded.add(recoveredData(1L, 11));
        store.repairedIncompleteTail = true;
        TimingNode node = node(store);

        node.activate();
        try {
            assertTrue(node.query(TimingNodeQueries.status()).timingDataTailRecovered());
            assertEquals(1, node.query(TimingNodeQueries.timingDataCount()).intValue());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void recoveryFailureLeavesWorkerQueryableInErrorAndRejectsNormalOperations() {
        RecordingStore store = new RecordingStore();
        store.failLoad = true;
        TimingNode node = node(store);

        node.activate();
        try {
            TimingNodeTypes.Status status = node.query(TimingNodeQueries.status());
            assertEquals(TimingNodeTypes.State.ERROR, status.state());
            assertFalse(status.hasLocation());
            assertEquals(1, status.problems().size());
            assertEquals(
                    TimingNodeTypes.ProblemCode.TIMING_DATA_RECOVERY_FAILED,
                    status.problems().get(0).code());
            assertEquals(
                    TimingNodeTypes.ProblemSeverity.ERROR,
                    status.problems().get(0).severity());
            assertTrue(status.problems().get(0).message().contains(
                    "expected recovery failure"));

            try {
                node.invoke(TimingNodeCommands.open(new LocationId(24)));
                fail("expected ERROR node to reject OPEN");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
                assertTrue(expected.getMessage().contains(
                        "expected recovery failure"));
            }

            try {
                node.query(TimingNodeQueries.timingDataCount());
                fail("expected ERROR node to reject LogBook query");
            } catch (TimingNodeTypes.OperationException expected) {
                assertEquals(
                        TimingNodeTypes.OperationException.Reason.FAILED,
                        expected.reason());
            }

            try {
                node.activate();
                fail("expected running node not to restart");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("cannot activate"));
            }
        } finally {
            node.deactivate();
        }
    }

    private static TimingData recoveredData(long sequence, int locationId) {
        return new DefaultTimingDataFactory().createManualRegistration(
                new Context(
                        new NodeId("TN-01"),
                        sequence,
                        new LocationId(locationId),
                        EFFECTIVE_TIME,
                        RECORDED_AT),
                new RegistrationId("recovered-" + sequence),
                ManualTimeSource.SYSTEM_ASSIGNED);
    }

    private static TimingNode node(RecordingStore store) {
        TimeSource timeSource = () -> RECORDED_AT;
        return new TimingNode(
                new NodeId("TN-01"),
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
                throw new PersistenceException("expected recovery failure");
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
