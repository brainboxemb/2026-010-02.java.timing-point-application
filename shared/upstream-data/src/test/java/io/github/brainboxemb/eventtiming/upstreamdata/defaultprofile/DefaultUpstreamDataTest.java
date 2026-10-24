package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class DefaultUpstreamDataTest {

    @Test
    public void timingDataAcceptsSixteenRecordsAndRejectsSeventeen() {
        List<TimingRecord> records = timingRecords(UpstreamData.MAX_TIMING_DATA_RECORDS);
        assertEquals(
                UpstreamData.MAX_TIMING_DATA_RECORDS,
                DefaultUpstreamData.timingData(
                        new NodeId("A"),
                        records)
                        .records()
                        .size());

        records.add(TimingRecord.createNodeOpened(
                'A',
                UpstreamData.MAX_TIMING_DATA_RECORDS + 1L,
                1,
                0L,
                0L));
        try {
            DefaultUpstreamData.timingData(
                    new NodeId("A"),
                    records);
            fail("expected oversized TIMING_DATA");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void raceDataAcceptsSixteenChangesAndRejectsSeventeenMixedChanges() {
        List<UpstreamData.TeamAssignmentChange> teams =
                new ArrayList<UpstreamData.TeamAssignmentChange>();
        List<UpstreamData.StageStartTimeChange> starts =
                new ArrayList<UpstreamData.StageStartTimeChange>();

        for (int i = 1; i <= 8; i++) {
            teams.add(
                    DefaultUpstreamData.teamAssignmentSet(
                            String.format("A%04d", i),
                            "0001"));
            starts.add(
                    DefaultUpstreamData.stageStartDelete(
                            String.format("R%04d", i)));
        }

        UpstreamData.RaceData accepted =
                DefaultUpstreamData.raceData(
                        new NodeId("A"),
                        new LocationId(7),
                        1L,
                        teams,
                        starts);
        assertEquals(new LocationId(7), accepted.locationId());

        starts.add(DefaultUpstreamData.stageStartDelete("R0009"));
        try {
            DefaultUpstreamData.raceData(
                    new NodeId("A"),
                    new LocationId(7),
                    2L,
                    teams,
                    starts);
            fail("expected oversized RACE_DATA");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void raceDataRequiresLocationContext() {
        try {
            DefaultUpstreamData.raceData(
                    new NodeId("A"),
                    null,
                    1L,
                    Collections.singletonList(
                            DefaultUpstreamData.teamAssignmentSet(
                                    "A0001",
                                    "0001")),
                    Collections.<UpstreamData.StageStartTimeChange>emptyList());
            fail("expected missing LocationId");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

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
    private static List<TimingRecord> timingRecords(int count) {
        List<TimingRecord> records = new ArrayList<TimingRecord>(count);
        for (int i = 1; i <= count; i++) {
            records.add(
                    TimingRecord.createNodeOpened(
                            'A',
                            i,
                            1,
                            0L,
                            0L));
        }
        return records;
    }

}
