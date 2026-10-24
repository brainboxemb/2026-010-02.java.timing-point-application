package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.fail;

public class DefaultUpstreamDataTest {

    @Test
    public void raceDataRejectsDuplicateReserveRegistrationKeys() {
        try {
            DefaultUpstreamData.raceData(
                    new NodeId("A"),
                    new LocationId(1),
                    1L,
                    Arrays.asList(
                            DefaultUpstreamData.teamAssignmentSet(
                                    "R0001",
                                    "0042"),
                            DefaultUpstreamData.teamAssignmentDelete(
                                    "R0001")),
                    Collections.<UpstreamData.StageStartTimeChange>emptyList());
            fail("expected invalid RaceData");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void raceDataRejectsDuplicateStageStartKeys() {
        try {
            DefaultUpstreamData.raceData(
                    new NodeId("A"),
                    new LocationId(1),
                    1L,
                    Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                    Arrays.asList(
                            DefaultUpstreamData.stageStartDelete("R0001"),
                            DefaultUpstreamData.stageStartDelete("R0001")));
            fail("expected invalid RaceData");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void ackIsRaceDataOnlyInPublicV1() {
        try {
            DefaultUpstreamData.ack(
                    new NodeId("A"),
                    UpstreamData.Stream.TIMING_DATA,
                    1L);
            fail("expected invalid ACK");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void reqRejectsSequenceZero() {
        try {
            DefaultUpstreamData.req(
                    new NodeId("A"),
                    UpstreamData.Stream.RACE_DATA,
                    0L);
            fail("expected invalid REQ");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
