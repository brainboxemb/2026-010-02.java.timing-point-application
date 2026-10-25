package oss.relay.timing.timingpoint.domain.node.logbook;

import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class LogBookDataTest {

    @Test
    public void nextSequenceFollowsCommittedStateWithoutConsumingIt() {
        LogBookData logBookData = new LogBookData(new NodeId("A"));

        assertEquals(1L, logBookData.nextSequence());
        assertEquals(1L, logBookData.nextSequence());

        logBookData.add(record("A", 1L, "N1001"));

        assertEquals(2L, logBookData.nextSequence());
        assertEquals(1, logBookData.size());
    }

    @Test
    public void latestReturnsNewestCommittedRecord() {
        LogBookData logBookData = new LogBookData(new NodeId("A"));
        TimingRecord first = record("A", 1L, "N1001");
        TimingRecord second = record("A", 2L, "N1002");

        logBookData.add(first);
        logBookData.add(second);

        assertEquals(second, logBookData.latest());
    }

    @Test
    public void rejectsSequenceGap() {
        LogBookData logBookData = new LogBookData(new NodeId("A"));

        try {
            logBookData.add(record("A", 2L, "N1002"));
            fail("expected sequence validation");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, logBookData.size());
            assertEquals(1L, logBookData.nextSequence());
        }
    }

    @Test
    public void rejectsDataFromDifferentTimingNode() {
        LogBookData logBookData = new LogBookData(new NodeId("A"));

        try {
            logBookData.add(record("B", 1L, "N1001"));
            fail("expected TimingNode validation");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, logBookData.size());
        }
    }

    @Test
    public void visitsBoundedRangesWithoutCreatingAReadList() {
        LogBookData logBookData = new LogBookData(new NodeId("A"));
        TimingRecord first = record("A", 1L, "N1001");
        TimingRecord second = record("A", 2L, "N1002");
        TimingRecord third = record("A", 3L, "N1003");
        logBookData.add(first);
        logBookData.add(second);
        logBookData.add(third);

        List<TimingRecord> visited = new ArrayList<>();
        logBookData.visitRange(2L, 1, visited::add);

        assertEquals(1, visited.size());
        assertEquals(second, visited.get(0));

        visited.clear();
        logBookData.visitLatest(2, visited::add);

        assertEquals(2, visited.size());
        assertEquals(second, visited.get(0));
        assertEquals(third, visited.get(1));
    }

    private static TimingRecord record(String nodeId, long sequence, String registrationId) {
        long effective = Instant.parse("2026-10-01T12:00:00Z").toEpochMilli();
        long recorded = Instant.parse("2026-10-01T12:00:01Z").toEpochMilli();
        return TimingRecord.createManualRegistration(
                nodeId.charAt(0),
                sequence,
                24,
                effective,
                recorded,
                new RegistrationId(registrationId).packedValue(),
                TimingRecord.Action.ADD,
                TimingRecord.ManualTimeSource.OPERATOR_ENTERED);
    }
}
