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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies the ordered registration list retains invalid rows. */
public class ActiveRegistrationsTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    @Test
    public void revokeMarksNewestMatchingRegistrationWithoutReorderingDisplay() {
        ActiveRegistrations registrations = new ActiveRegistrations();

        TimingData first = registration(1L, 1, TimingData.RegistrationAction.ADD);
        TimingData second = registration(2L, 1, TimingData.RegistrationAction.ADD);
        TimingData third = registration(3L, 2, TimingData.RegistrationAction.ADD);
        TimingData revoke = registration(4L, 1, TimingData.RegistrationAction.REV);
        TimingData lifecycle = FACTORY.createNodeOpen(context(5L, 1));

        registrations.apply(first);
        registrations.apply(second);
        registrations.apply(third);
        ActiveRegistrations.Change change = registrations.apply(revoke);
        assertNull(registrations.apply(lifecycle));

        List<ActiveRegistrations.Registration> wp1 = new ArrayList<>();
        List<ActiveRegistrations.Registration> wp2 = new ArrayList<>();
        assertEquals(2, registrations.visit(new LocationId(1), 10, wp1::add));
        assertEquals(1, registrations.visit(new LocationId(2), 10, wp2::add));
        assertEquals(1L, wp1.get(0).sequenceNumber());
        assertEquals(2L, wp1.get(1).sequenceNumber());
        assertTrue(wp1.get(0).isValid());
        assertFalse(wp1.get(1).isValid());
        assertTrue(wp2.get(0).isValid());
        assertEquals(2L, change.registration().sequenceNumber());
        assertEquals(1, registrations.visit(new LocationId(1), 1, ignored -> {}));
    }

    @Test
    public void unmatchedRevokeDoesNotChangeEarlierEntries() {
        ActiveRegistrations registrations = new ActiveRegistrations();
        registrations.apply(registration(1L, 1, TimingData.RegistrationAction.ADD));

        TimingTimestamp different = new TimingTimestamp(Instant.parse("2026-10-13T12:05:00Z"));
        TimingData unmatched = FACTORY.createAutomaticRegistration(
                new TimingDataFactory.Context(NODE_ID, 2L, new LocationId(1), different, different),
                new RegistrationId("A0001"), TimingData.RegistrationAction.REV);

        assertNull(registrations.apply(unmatched));
        List<ActiveRegistrations.Registration> rows = new ArrayList<>();
        registrations.visit(new LocationId(1), 10, rows::add);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isValid());
    }

    private static TimingData registration(long sequence, int location, TimingData.RegistrationAction action) {
        return FACTORY.createAutomaticRegistration(
                context(sequence, location), new RegistrationId("A0001"), action);
    }

    private static TimingDataFactory.Context context(long sequence, int location) {
        TimingTimestamp time = new TimingTimestamp(Instant.parse("2026-10-13T12:00:00Z"));
        return new TimingDataFactory.Context(NODE_ID, sequence, new LocationId(location), time, time);
    }
}
