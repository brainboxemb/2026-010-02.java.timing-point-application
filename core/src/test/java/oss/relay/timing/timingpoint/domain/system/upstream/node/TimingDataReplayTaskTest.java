package oss.relay.timing.timingpoint.domain.system.upstream.node;

import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookAccessor;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookCommand;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookQuery;
import oss.relay.timing.timingpoint.domain.node.logbook.LogBookStatus;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class TimingDataReplayTaskTest {

    @Test
    public void waitsForLogBookBatchBeforeSendingReplay() {
        TestUpstreamStream stream = new TestUpstreamStream();
        CompletableFuture<List<TimingRecord>> batch =
                new CompletableFuture<List<TimingRecord>>();
        TimingDataSync sync =
                new TimingDataSync(
                        new NodeId("A"),
                        stream::isAvailable,
                        stream::send,
                        new PendingLogBook(1L, batch));
        DownstreamDelivery delivery =
                new DownstreamDelivery(
                        stream::isAvailable,
                        stream::send);
        UpstreamData packet =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        1L,
                        DefaultUpstreamData.createTimingDataRequest(1L));
        assertEquals(true, delivery.begin(packet));
        TimingDataReplayTask task =
                new TimingDataReplayTask(
                        stream::isAvailable,
                        sync,
                        (UpstreamData.TimingDataRequest) packet.message(),
                        () -> delivery.complete(packet),
                        () -> {
                        });

        assertSame(TaskStep.Type.AWAIT, task.runStep().type());
        assertEquals(1, stream.sentCount());
        UpstreamData.Receive receive =
                (UpstreamData.Receive) stream.takeSent();
        assertEquals(1L, receive.receivedPacketNr());

        batch.complete(
                Collections.singletonList(
                        timingRecord(1)));

        assertSame(TaskStep.Type.DONE, task.runStep().type());
        UpstreamData.TimingDataTransfer transfer =
                (UpstreamData.TimingDataTransfer) stream.takeSent();
        assertEquals(1, transfer.records().size());
        assertEquals(1L, transfer.records().get(0).sequenceNumber());
    }

    @Test
    public void delaysOnlyBetweenSuccessiveReplayPackets() {
        TestUpstreamStream stream = new TestUpstreamStream();
        List<TimingRecord> firstBatch = new ArrayList<TimingRecord>();
        for (int sequence = 1; sequence <= 16; sequence++) {
            firstBatch.add(timingRecord(sequence));
        }
        TimingDataSync sync =
                new TimingDataSync(
                        new NodeId("A"),
                        stream::isAvailable,
                        stream::send,
                        new SequencedLogBook(
                                17L,
                                firstBatch,
                                Collections.singletonList(
                                        timingRecord(17))));
        TimingDataReplayTask task =
                new TimingDataReplayTask(
                        stream::isAvailable,
                        sync,
                        DefaultUpstreamData.createTimingDataRequest(1L),
                        () -> { },
                        () -> { },
                        Duration.ofMillis(250L));

        assertSame(TaskStep.Type.AWAIT, task.runStep().type());

        TaskStep delay = task.runStep();
        assertSame(TaskStep.Type.AFTER, delay.type());
        assertEquals(Duration.ofMillis(250L), delay.delay());
        UpstreamData.TimingDataTransfer first =
                (UpstreamData.TimingDataTransfer) stream.takeSent();
        assertEquals(16, first.records().size());

        assertSame(TaskStep.Type.AWAIT, task.runStep().type());
        assertSame(TaskStep.Type.DONE, task.runStep().type());
        UpstreamData.TimingDataTransfer last =
                (UpstreamData.TimingDataTransfer) stream.takeSent();
        assertEquals(1, last.records().size());
        assertEquals(17L, last.records().get(0).sequenceNumber());
    }

    private static TimingRecord timingRecord(int sequence) {
        long timestamp =
                Instant.parse("2026-10-13T12:00:00Z")
                        .plusMillis(sequence)
                        .toEpochMilli();
        return TimingRecord.createAutomaticRegistration(
                'A',
                sequence,
                1,
                timestamp,
                timestamp,
                new RegistrationId("A0001").packedValue(),
                TimingRecord.Action.ADD,
                null,
                null);
    }

    private static final class SequencedLogBook
            implements LogBookAccessor {
        private final long head;
        private final Deque<List<TimingRecord>> batches =
                new ArrayDeque<List<TimingRecord>>();

        @SafeVarargs
        private SequencedLogBook(
                long head,
                List<TimingRecord>... batches) {
            this.head = head;
            Collections.addAll(
                    this.batches,
                    batches);
        }

        @Override
        public LogBookStatus status() {
            return null;
        }

        @Override
        public <R> R process(LogBookCommand<R> command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(LogBookCommand<?> command) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> CompletableFuture<R> submit(LogBookQuery<R> query) {
            return (CompletableFuture<R>) (CompletableFuture<?>)
                    CompletableFuture.completedFuture(
                            batches.removeFirst());
        }

        @Override
        public <R> R process(LogBookQuery<R> query) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> R process(
                LogBookQuery<R> query,
                LogBookQuery.ReadConsistency consistency) {
            return (R) Long.valueOf(head);
        }
    }

    private static final class PendingLogBook
            implements LogBookAccessor {
        private final long head;
        private final CompletableFuture<List<TimingRecord>> batch;

        private PendingLogBook(
                long head,
                CompletableFuture<List<TimingRecord>> batch) {
            this.head = head;
            this.batch = batch;
        }

        @Override
        public LogBookStatus status() {
            return null;
        }

        @Override
        public <R> R process(LogBookCommand<R> command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void execute(LogBookCommand<?> command) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> CompletableFuture<R> submit(LogBookQuery<R> query) {
            return (CompletableFuture<R>) (CompletableFuture<?>) batch;
        }

        @Override
        public <R> R process(LogBookQuery<R> query) {
            throw new UnsupportedOperationException();
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> R process(
                LogBookQuery<R> query,
                LogBookQuery.ReadConsistency consistency) {
            return (R) Long.valueOf(head);
        }
    }
}
