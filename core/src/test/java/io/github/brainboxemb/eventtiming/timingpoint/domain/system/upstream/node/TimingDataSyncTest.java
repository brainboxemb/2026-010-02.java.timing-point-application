package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;

import java.time.Instant;
import java.util.Collections;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimingDataSyncTest {

    @Test
    public void raceDataReqIsNotOwnedByTimingDataSync() {
        NodeId nodeId = new NodeId("A");
        TimingDataSync sync =
                new TimingDataSync(nodeId, new TestUpstreamStream(), emptyLogBook(nodeId));

        try {
            sync.startReplay(
                    DefaultUpstreamData.req(
                            nodeId,
                            UpstreamData.Stream.RACE_DATA,
                            1L));
            fail("expected unsupported REQ direction");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("TIMING_DATA"));
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
