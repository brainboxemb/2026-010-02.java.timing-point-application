package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookAccessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookStatus;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.Collections;
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
        assertEquals(1L, receive.receivedPacketId());

        batch.complete(
                Collections.singletonList(
                        timingRecord(1)));

        assertSame(TaskStep.Type.DONE, task.runStep().type());
        UpstreamData.TimingDataTransfer transfer =
                (UpstreamData.TimingDataTransfer) stream.takeSent();
        assertEquals(1, transfer.records().size());
        assertEquals(1L, transfer.records().get(0).sequenceNumber());
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
