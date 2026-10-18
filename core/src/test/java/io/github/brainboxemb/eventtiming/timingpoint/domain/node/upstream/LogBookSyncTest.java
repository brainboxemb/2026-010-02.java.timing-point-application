package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
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

public class LogBookSyncTest {

    @Test
    public void raceDataReqIsNotOwnedByLogBookSync() {
        NodeId nodeId = new NodeId("A");
        LogBookSync sync =
                new LogBookSync(nodeId, new TestUpstreamStream(), emptyLogBook(nodeId));

        try {
            sync.handleReq(
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
                                Collections.<TimingData>emptyList(),
                                false);
                    }

                    @Override
                    public void append(TimingData data) {
                        // Not used by this protocol-direction test.
                    }
                },
                new DefaultTimingDataFactory(),
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
