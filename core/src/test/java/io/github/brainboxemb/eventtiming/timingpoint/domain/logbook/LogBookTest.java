package io.github.brainboxemb.eventtiming.timingpoint.domain.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class LogBookTest {
    private final DefaultTimingDataFactory factory = new DefaultTimingDataFactory();

    @Test
    public void nextSequenceFollowsCommittedStateWithoutConsumingIt() {
        LogBook logBook = new LogBook(new NodeId("A"));

        assertEquals(1L, logBook.nextSequence());
        assertEquals(1L, logBook.nextSequence());

        logBook.add(data("A", 1L, "1001"));

        assertEquals(2L, logBook.nextSequence());
        assertEquals(1, logBook.size());
    }

    @Test
    public void rejectsSequenceGap() {
        LogBook logBook = new LogBook(new NodeId("A"));

        try {
            logBook.add(data("A", 2L, "1002"));
            fail("expected sequence validation");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, logBook.size());
            assertEquals(1L, logBook.nextSequence());
        }
    }

    @Test
    public void rejectsDataFromDifferentTimingNode() {
        LogBook logBook = new LogBook(new NodeId("A"));

        try {
            logBook.add(data("B", 1L, "1001"));
            fail("expected TimingNode validation");
        } catch (IllegalArgumentException expected) {
            assertEquals(0, logBook.size());
        }
    }

    @Test
    public void visitsBoundedRangesWithoutCreatingAReadList() {
        LogBook logBook = new LogBook(new NodeId("A"));
        TimingData first = data("A", 1L, "1001");
        TimingData second = data("A", 2L, "1002");
        TimingData third = data("A", 3L, "1003");
        logBook.add(first);
        logBook.add(second);
        logBook.add(third);

        List<TimingData> visited = new ArrayList<>();
        logBook.visitRange(2L, 1, visited::add);

        assertEquals(1, visited.size());
        assertEquals(second, visited.get(0));

        visited.clear();
        logBook.visitLatest(2, visited::add);

        assertEquals(2, visited.size());
        assertEquals(second, visited.get(0));
        assertEquals(third, visited.get(1));
    }

    private TimingData data(String nodeId, long sequence, String registrationId) {
        TimingTimestamp effective =
                TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
        TimingTimestamp recorded =
                TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");
        Context context =
                new Context(new NodeId(nodeId), sequence, new LocationId(24), effective, recorded);
        return factory.createManualRegistration(
                context,
                new RegistrationId(registrationId),
                ManualTimeSource.OPERATOR_ENTERED);
    }
}
