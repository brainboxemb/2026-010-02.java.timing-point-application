package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.LogBook;
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
                new LogBookSync(nodeId, new TestUpstreamStream(), new LogBook(nodeId));

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
}
