package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimingDataSyncTest {

    @Test
    public void liveCatchUpSplitsPendingSuffixIntoSixteenRecordBatches() {
        NodeId nodeId = new NodeId("A");
        LogBook logBook = emptyLogBook(nodeId);
        List<UpstreamData.Message> sent = new ArrayList<UpstreamData.Message>();
        activate(logBook);
        try {
            for (int index = 0; index < 17; index++) {
                logBook.process(
                        LogBookCommands.commitNodeOpen(
                                new LocationId(1)));
            }

            TimingDataSync sync =
                    new TimingDataSync(
                            () -> true,
                            sent::add,
                            logBook);
            sync.startReplay(
                    DefaultUpstreamData.createTimingDataRequest(
                            18L));
            assertTrue(sync.liveReady());

            for (int index = 0; index < 17; index++) {
                logBook.process(
                        LogBookCommands.commitNodeOpen(
                                new LocationId(1)));
            }

            sync.sendNextLiveBatch(sync.submitNextLiveBatch().join());
            sync.sendNextLiveBatch(sync.submitNextLiveBatch().join());

            assertEquals(2, sent.size());
            UpstreamData.TimingDataTransfer first =
                    (UpstreamData.TimingDataTransfer) sent.get(0);
            UpstreamData.TimingDataTransfer second =
                    (UpstreamData.TimingDataTransfer) sent.get(1);
            assertEquals(UpstreamData.MAX_TIMING_DATA_RECORDS, first.records().size());
            assertEquals(18L, first.records().get(0).sequenceNumber());
            assertEquals(33L, first.records().get(15).sequenceNumber());
            assertEquals(1, second.records().size());
            assertEquals(34L, second.records().get(0).sequenceNumber());
        } finally {
            deactivate(logBook);
        }
    }

    @Test
    public void sequenceRequestReturnsCurrentLogBookHead() {
        NodeId nodeId = new NodeId("A");
        LogBook logBook = emptyLogBook(nodeId);
        List<UpstreamData.Message> sent =
                new ArrayList<UpstreamData.Message>();
        activate(logBook);
        try {
            logBook.process(
                    LogBookCommands.commitNodeOpen(
                            new LocationId(1)));

            TimingDataSync sync =
                    new TimingDataSync(
                            () -> true,
                            sent::add,
                            logBook);
            sync.handleSequence(
                    DefaultUpstreamData.createSequenceRequest());

            assertEquals(1, sent.size());
            UpstreamData.Sequence answer =
                    (UpstreamData.Sequence) sent.get(0);
            assertEquals(
                    UpstreamData.SequenceOperation.ANS,
                    answer.operation());
            assertEquals(
                    Long.valueOf(1L),
                    answer.sequence());
        } finally {
            deactivate(logBook);
        }
    }

    private static LogBook emptyLogBook(NodeId nodeId) {
        return new LogBook(
                nodeId,
                new TimingDataPersistence() {
                    @Override
                    public LoadResult load() {
                        return new LoadResult(
                                Collections.<TimingRecord>emptyList(),
                                false);
                    }

                    @Override
                    public void append(TimingRecord record) {
                        // Not used by this protocol-direction test.
                    }
                },
                () -> Instant.EPOCH,
                SystemMonotonicClock.INSTANCE,
                new LogBookMetrics(),
                Runnable::run,
                8,
                8,
                LogBook.DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS,
                LogBook.DEFAULT_ORDERED_QUERY_WAIT_TIMEOUT_MILLIS);
    }
}
