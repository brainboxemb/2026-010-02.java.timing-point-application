package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class DefaultUpstreamDataTest {

    @Test
    public void timingDataAcceptsSixteenRecordsAndRejectsSeventeen() {
        List<TimingRecord> records =
                timingRecords(UpstreamData.MAX_TIMING_DATA_RECORDS);
        assertEquals(
                UpstreamData.MAX_TIMING_DATA_RECORDS,
                DefaultUpstreamData.createTimingData(records)
                        .records()
                        .size());

        records.add(
                TimingRecord.createNodeOpened(
                        'A',
                        UpstreamData.MAX_TIMING_DATA_RECORDS + 1L,
                        1,
                        0L,
                        0L));
        try {
            DefaultUpstreamData.createTimingData(records);
            fail("expected oversized TIMING_DATA");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void raceDataHasLocationButNoIndependentSequence() {
        List<UpstreamData.TeamAssignmentChange> teams =
                new ArrayList<UpstreamData.TeamAssignmentChange>();
        List<UpstreamData.StageStartTimeChange> starts =
                new ArrayList<UpstreamData.StageStartTimeChange>();

        for (int i = 1; i <= 8; i++) {
            teams.add(
                    DefaultUpstreamData.createTeamAssignmentSet(
                            String.format("A%04d", i),
                            "0001"));
            starts.add(
                    DefaultUpstreamData.createStageStartDelete(
                            String.format("R%04d", i)));
        }

        UpstreamData.RaceData accepted =
                DefaultUpstreamData.createRaceData(
                        new LocationId(7),
                        teams,
                        starts);
        assertEquals(new LocationId(7), accepted.locationId());

        starts.add(
                DefaultUpstreamData.createStageStartDelete("R0009"));
        try {
            DefaultUpstreamData.createRaceData(
                    new LocationId(7),
                    teams,
                    starts);
            fail("expected oversized RACE_DATA");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void raceDataRejectsMissingLocationAndDuplicateKeys() {
        try {
            DefaultUpstreamData.createRaceData(
                    null,
                    Collections.singletonList(
                            DefaultUpstreamData.createTeamAssignmentSet(
                                    "A0001",
                                    "0001")),
                    Collections.<UpstreamData.StageStartTimeChange>emptyList());
            fail("expected missing LocationId");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            DefaultUpstreamData.createRaceData(
                    new LocationId(1),
                    Arrays.asList(
                            DefaultUpstreamData.createTeamAssignmentSet(
                                    "R0001",
                                    "0042"),
                            DefaultUpstreamData.createTeamAssignmentDelete(
                                    "R0001")),
                    Collections.<UpstreamData.StageStartTimeChange>emptyList());
            fail("expected duplicate RaceData key");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void packetDirectionAndPingIdentityAreValidated() {
        NodeId nodeId = new NodeId("A");
        UpstreamData.Ping ping =
                DefaultUpstreamData.createPing(
                        UpstreamData.PingOperation.REQ,
                        UpstreamData.PingMode.HB,
                        "hb-1");

        UpstreamData pingPacket =
                DefaultUpstreamData.createUpPacket(
                        nodeId,
                        0L,
                        ping);
        assertSame(UpstreamData.Direction.UP_PACKET, pingPacket.direction());
        assertEquals(0L, pingPacket.packetId());

        try {
            DefaultUpstreamData.createUpPacket(
                    nodeId,
                    1L,
                    ping);
            fail("expected positive PING packetId rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            DefaultUpstreamData.createUpPacket(
                    nodeId,
                    1L,
                    DefaultUpstreamData.createRaceData(
                            new LocationId(1),
                            Collections.singletonList(
                                    DefaultUpstreamData.createTeamAssignmentDelete(
                                            "R0001")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));
            fail("expected RACE_DATA direction rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void sequenceOperationsKeepRequestSeparateFromPosition() {
        UpstreamData.Sequence request =
                DefaultUpstreamData.createSequenceRequest();
        assertSame(
                UpstreamData.SequenceOperation.REQ,
                request.operation());
        assertNull(request.sequence());

        UpstreamData.Sequence answer =
                DefaultUpstreamData.createSequenceAnswer(42L);
        assertSame(
                UpstreamData.SequenceOperation.ANS,
                answer.operation());
        assertEquals(Long.valueOf(42L), answer.sequence());

        UpstreamData.Sequence notification =
                DefaultUpstreamData.createSequenceNotification(0L);
        assertSame(
                UpstreamData.SequenceOperation.NTF,
                notification.operation());
        assertEquals(Long.valueOf(0L), notification.sequence());
    }

    @Test
    public void timingDataRequestRequiresPositiveSequenceAndReceiveAllowsZero() {
        try {
            DefaultUpstreamData.createTimingDataRequest(0L);
            fail("expected invalid TIMING_DATA_REQ");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        assertEquals(
                0L,
                DefaultUpstreamData.createReceive(0L)
                        .receivedPacketId());
    }

    private static List<TimingRecord> timingRecords(int count) {
        List<TimingRecord> records =
                new ArrayList<TimingRecord>(count);
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
