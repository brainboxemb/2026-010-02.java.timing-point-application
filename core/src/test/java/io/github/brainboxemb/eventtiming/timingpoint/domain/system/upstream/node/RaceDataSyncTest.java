package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class RaceDataSyncTest {

    @Test
    public void centralSourceHeadBehindAppliedSequenceIsRejected() {
        TestUpstreamStream stream = new TestUpstreamStream();
        RaceDataSync sync = new RaceDataSync(new NodeId("A"), stream::isAvailable, stream::send);

        assertTrue(sync.beginApply(raceData(1L)));
        sync.completeApply(1L);
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
    public void duplicateSequenceIsAckedWithoutAnotherApply() {
        TestUpstreamStream stream = new TestUpstreamStream();
        RaceDataSync sync =
                new RaceDataSync(
                        new NodeId("A"),
                        stream::isAvailable,
                        stream::send);

        assertTrue(sync.beginApply(raceData(1L)));
        sync.completeApply(1L);
        stream.takeSent();

        assertFalse(sync.beginApply(raceData(1L)));
        UpstreamData.Ack repeatedAck =
                (UpstreamData.Ack) stream.takeSent();
        assertTrue(repeatedAck.sequence() == 1L);
    }

    @Test
    public void timingDataSyncIsNotOwnedByRaceDataSync() {
        RaceDataSync sync =
                new RaceDataSync(
                        new NodeId("A"),
                        () -> true,
                        data -> {
                        });
        try {
            sync.handleSync(
                    DefaultUpstreamData.sync(
                            new NodeId("A"),
                            UpstreamData.Stream.TIMING_DATA,
                            0L));
            fail("expected unsupported SYNC direction");
        } catch (UnsupportedOperationException expected) {
            assertTrue(expected.getMessage().contains("RACE_DATA"));
        }
    }

    private static UpstreamData.RaceData raceData(long sequence) {
        return DefaultUpstreamData.raceData(
                new NodeId("A"),
                new LocationId(1),
                sequence,
                Collections.singletonList(
                        DefaultUpstreamData.teamAssignmentSet(
                                "R0001",
                                "0042")),
                Collections.<UpstreamData.StageStartTimeChange>emptyList());
    }
}
