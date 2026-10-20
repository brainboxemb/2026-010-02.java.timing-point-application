package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpstreamDataSyncTest {

    @Test
    public void centralSourceHeadBehindAppliedSequenceIsRejected() {
        TestUpstreamStream stream = new TestUpstreamStream();
        UpstreamDataSync sync = sync(stream);

        sync.handleRaceData(
                DefaultUpstreamData.raceData(
                        new NodeId("A"),
                        1L,
                        Collections.singletonList(
                                DefaultUpstreamData.teamAssignmentSet("R0001", "0042")),
                        Collections.<UpstreamData.StageStartTimeChange>emptyList()))
                .toCompletableFuture()
                .join();
        sync.completeRaceData(1L);
        stream.takeSent(); // ACK for sequence 1.

        try {
            sync.handleSync(
                    DefaultUpstreamData.sync(
                            new NodeId("A"),
                            UpstreamData.Stream.RACE_DATA,
                            0L));
            fail("expected RACE_DATA source-head conflict");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("behind"));
        }
    }

    @Test
    public void timingDataSyncIsNotOwnedByUpstreamDataSync() {
        try {
            sync(new TestUpstreamStream()).handleSync(
                    DefaultUpstreamData.sync(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            0L));
            fail("expected unsupported SYNC direction");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("RACE_DATA"));
        }
    }

    private static UpstreamDataSync sync(TestUpstreamStream stream) {
        return new UpstreamDataSync(
                new NodeId("A"),
                stream,
                ignored -> CompletableFuture.completedFuture(null));
    }
}
