package oss.relay.timing.upstreamdata.defaultprofile;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.upstreamdata.UpstreamData;

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
    public void currentStateMessagesHaveIndependentSixteenChangeBounds() {
        List<UpstreamData.TeamAssignmentChange> teams =
                new ArrayList<UpstreamData.TeamAssignmentChange>();
        List<UpstreamData.StageStartTimeChange> starts =
                new ArrayList<UpstreamData.StageStartTimeChange>();

        for (int i = 1; i <= 16; i++) {
            teams.add(
                    DefaultUpstreamData.createTeamAssignmentSet(
                            String.format("A%04d", i),
                            String.format("%04d", i)));
            starts.add(
                    DefaultUpstreamData.createStageStartDelete(
                            String.format("R%04d", i)));
        }

        assertEquals(
                16,
                DefaultUpstreamData.createTeamAssignments(teams)
                        .changes()
                        .size());
        assertEquals(
                16,
                DefaultUpstreamData.createStageStarts(starts)
                        .changes()
                        .size());

        teams.add(
                DefaultUpstreamData.createTeamAssignmentDelete("A0017"));
        try {
            DefaultUpstreamData.createTeamAssignments(teams);
            fail("expected oversized TEAM_ASSIGNMENTS");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        starts.add(
                DefaultUpstreamData.createStageStartDelete("R0017"));
        try {
            DefaultUpstreamData.createStageStarts(starts);
            fail("expected oversized STAGE_STARTS");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void currentStateMessagesRejectDuplicateRegistrationKeys() {
        try {
            DefaultUpstreamData.createTeamAssignments(
                    Arrays.asList(
                            DefaultUpstreamData.createTeamAssignmentSet(
                                    "R0001",
                                    "0042"),
                            DefaultUpstreamData.createTeamAssignmentDelete(
                                    "R0001")));
            fail("expected duplicate TEAM_ASSIGNMENTS key");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            DefaultUpstreamData.createStageStarts(
                    Arrays.asList(
                            DefaultUpstreamData.createStageStartDelete("R0001"),
                            DefaultUpstreamData.createStageStartDelete("R0001")));
            fail("expected duplicate STAGE_STARTS key");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    public void packetDirectionAndPingNumberAreValidated() {
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
        assertEquals(0L, pingPacket.packetNr());

        try {
            DefaultUpstreamData.createUpPacket(
                    nodeId,
                    1L,
                    ping);
            fail("expected positive PING packetNr rejection");
        } catch (IllegalArgumentException expected) {
            // expected
        }

        try {
            DefaultUpstreamData.createUpPacket(
                    nodeId,
                    1L,
                    DefaultUpstreamData.createTeamAssignments(
                            Collections.singletonList(
                                    DefaultUpstreamData.createTeamAssignmentDelete(
                                            "R0001"))));
            fail("expected TEAM_ASSIGNMENTS direction rejection");
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
                        .receivedPacketNr());
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
