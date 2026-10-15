package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies the compact active-registration fold used instead of shadow TimingData history. */
public class ActiveRegistrationsTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    /** Verifies lifecycle records are ignored and one REV removes only one matching active ADD. */
    @Test
    public void foldsHistoryIntoOnlyCurrentlyActiveRegistrations() {
        ActiveRegistrations active = new ActiveRegistrations();
        TimingData first = registration(1L, TimingData.RegistrationAction.ADD);
        TimingData second = registration(2L, TimingData.RegistrationAction.ADD);
        TimingData lifecycle = FACTORY.createNodeOpen(context(3L, Instant.parse("2026-10-13T12:01:00Z")));
        TimingData revoke = registration(4L, TimingData.RegistrationAction.REV);

        active.apply(first);
        active.apply(second);
        active.apply(lifecycle);
        active.apply(revoke);

        List<TimingData> remaining = values(active);
        assertEquals(1, remaining.size());
        assertEquals(first.sequenceNumber(), remaining.get(0).sequenceNumber());
    }

    /** Copies the package-private live view only for assertion isolation in this unit test. */
    private static List<TimingData> values(ActiveRegistrations active) {
        List<TimingData> result = new ArrayList<TimingData>();
        for (TimingData data : active.values()) {
            result.add(data);
        }
        return result;
    }

    /** Creates one automatic registration with the shared registration identity used by the fold test. */
    private static TimingData registration(long sequence, TimingData.RegistrationAction action) {
        Instant effectiveTime = Instant.parse("2026-10-13T12:00:00Z");
        return FACTORY.createAutomaticRegistration(
                context(sequence, effectiveTime),
                new RegistrationId("RT-A-0001"),
                action);
    }

    /** Creates one committed TimingData context for this test node. */
    private static TimingDataFactory.Context context(long sequence, Instant effectiveTime) {
        TimingTimestamp timestamp = new TimingTimestamp(effectiveTime);
        return new TimingDataFactory.Context(NODE_ID, sequence, LOCATION_ID, timestamp, timestamp);
    }
}
