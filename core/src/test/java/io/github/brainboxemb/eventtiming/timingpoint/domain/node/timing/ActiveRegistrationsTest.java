package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
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

/** Verifies active registration state and per-location team counts. */
public class ActiveRegistrationsTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    /** Verifies lifecycle records are ignored and one REV removes only one matching active ADD. */
    @Test
    public void foldsRecordsIntoOnlyCurrentlyActiveRegistrations() {
        ActiveRegistrations active = new ActiveRegistrations();
        TimingData first = registration(1L, TimingData.RegistrationAction.ADD);
        TimingData second = registration(2L, TimingData.RegistrationAction.ADD);
        TimingData lifecycle = FACTORY.createNodeOpen(context(3L, Instant.parse("2026-10-13T12:01:00Z")));
        TimingData revoke = registration(4L, TimingData.RegistrationAction.REV);

        active.apply(first, id -> null);
        active.apply(second, id -> null);
        active.apply(lifecycle, id -> null);
        active.apply(revoke, id -> null);

        List<TimingData> remaining = values(active);
        assertEquals(1, remaining.size());
        assertEquals(first.sequenceNumber(), remaining.get(0).sequenceNumber());
    }

    /** Counts teams separately by location while counting multiple ADDs only once. */
    @Test
    public void uniqueTeamCountsAreMaintainedPerLocation() {
        ActiveRegistrations active = new ActiveRegistrations();
        TeamId teamId = new TeamId("0001");

        active.apply(registration(1L, TimingData.RegistrationAction.ADD),
                id -> teamId);
        active.apply(registration(2L, TimingData.RegistrationAction.ADD),
                id -> teamId);
        active.apply(registrationAt(3L, 2, TimingData.RegistrationAction.ADD),
                id -> teamId);

        assertEquals(1, active.uniqueTeamCount(LOCATION_ID));
        assertEquals(1, active.uniqueTeamCount(new LocationId(2)));

        active.apply(registration(4L, TimingData.RegistrationAction.REV),
                id -> teamId);
        assertEquals(1, active.uniqueTeamCount(LOCATION_ID));
        active.apply(registration(5L, TimingData.RegistrationAction.REV),
                id -> teamId);
        assertEquals(0, active.uniqueTeamCount(LOCATION_ID));
        assertEquals(1, active.uniqueTeamCount(new LocationId(2)));
    }

    /** Different registration IDs for one team count once at the same location. */
    @Test
    public void multipleRegistrationIdsForOneTeamCountOnce() {
        ActiveRegistrations active = new ActiveRegistrations();
        TeamId teamId = new TeamId("0001");
        active.apply(registration(1L, TimingData.RegistrationAction.ADD), id -> teamId);
        TimingTimestamp timestamp =
                new TimingTimestamp(Instant.parse("2026-10-13T12:00:00Z"));
        TimingData second = FACTORY.createAutomaticRegistration(
                new TimingDataFactory.Context(
                        NODE_ID, 2L, LOCATION_ID, timestamp, timestamp),
                new RegistrationId("RT-X-0001"));
        active.apply(second, id -> teamId);

        assertEquals(1, active.uniqueTeamCount(LOCATION_ID));
        assertEquals(1, active.uniqueTeamCount());

        active.apply(registration(3L, TimingData.RegistrationAction.REV), id -> teamId);
        assertEquals(1, active.uniqueTeamCount(LOCATION_ID));
    }

    /** A REV with an unmatched original time must not change any count. */
    @Test
    public void unmatchedRevokeDoesNotChangeCounts() {
        ActiveRegistrations active = new ActiveRegistrations();
        TeamId teamId = new TeamId("0001");
        active.apply(registration(1L, TimingData.RegistrationAction.ADD),
                id -> teamId);

        Instant differentTime = Instant.parse("2026-10-13T12:05:00Z");
        TimingData unmatched = FACTORY.createAutomaticRegistration(
                context(2L, differentTime),
                new RegistrationId("RT-A-0001"),
                TimingData.RegistrationAction.REV);
        active.apply(unmatched, id -> teamId);

        assertEquals(1, active.uniqueTeamCount(LOCATION_ID));
        assertEquals(1, values(active).size());
    }

    /** Updating an assignment moves counts without applying another ADD. */
    @Test
    public void assignmentChangesUpdateTheExistingCount() {
        ActiveRegistrations active = new ActiveRegistrations();
        RegistrationId registrationId = new RegistrationId("RT-A-0001");
        active.apply(registration(1L, TimingData.RegistrationAction.ADD),
                id -> null);
        assertEquals(0, active.uniqueTeamCount(LOCATION_ID));

        active.teamAssignmentChanged(registrationId, new TeamId("0001"));
        assertEquals(1, active.uniqueTeamCount(LOCATION_ID));
        active.teamAssignmentChanged(registrationId, new TeamId("0002"));
        assertEquals(1, active.uniqueTeamCount(LOCATION_ID));
        active.teamAssignmentChanged(registrationId, null);
        assertEquals(0, active.uniqueTeamCount(LOCATION_ID));
    }

    private static TimingData registrationAt(
            long sequence,
            int location,
            TimingData.RegistrationAction action) {
        TimingTimestamp timestamp =
                new TimingTimestamp(Instant.parse("2026-10-13T12:00:00Z"));
        return FACTORY.createAutomaticRegistration(
                new TimingDataFactory.Context(
                        NODE_ID,
                        sequence,
                        new LocationId(location),
                        timestamp,
                        timestamp),
                new RegistrationId("RT-A-0001"),
                action);
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
