package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecords;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class LogBookDataTest {
    private final DefaultTimingDataFactory factory = new DefaultTimingDataFactory();

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

    private TimingRecord record(String nodeId, long sequence, String registrationId) {
        TimingTimestamp effective =
                TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
        TimingTimestamp recorded =
                TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");
        Context context =
                new Context(new NodeId(nodeId), sequence, new LocationId(24), effective, recorded);
        TimingData data =
                factory.createManualRegistration(
                        context,
                        new RegistrationId(registrationId),
                        ManualTimeSource.OPERATOR_ENTERED);
        return TimingRecords.fromTimingData(data);
    }
}
