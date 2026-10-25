package oss.relay.timing.timingpoint.domain.node.service.state;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingpoint.domain.node.registration.ActiveRegistrations;
import oss.relay.timing.timingpoint.domain.node.registration.RegisteredTeams;
import oss.relay.timing.timingpoint.platform.events.EventSource;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Read-only boundary for one TimingNode's current data.
 *
 * <p>Registration reads wait until the current OPEN location has caught up with LogBook.
 * RaceData reads are ordered on the NodeService Task Lane but do not depend on that catch-up.</p>
 */
public interface NodeStateAccessor {

    /** Publishes live team membership changes after historical reconstruction has completed. */
    EventSource<RegisteredTeams.Change> registeredTeamsChanged();

    /** Reads active-WP projection metadata after LogBook catch-up. */
    RegistrationProjectionInfo registrationProjectionInfo();

    /** Visits one bounded active-WP page and reads its metadata in the same ordered turn. */
    RegistrationProjectionInfo visitRegistrationPage(
            long afterSequence,
            int limit,
            Consumer<ActiveRegistrations.Registration> visitor);

    /** Reads the maintained unique-team count for the active WP. */
    int uniqueTeamRegistrationCount();

    /** Visits a bounded prefix of the active-WP registration rows. */
    int visitRegistrations(
            int limit,
            Consumer<ActiveRegistrations.Registration> visitor);

    /** Visits bounded registration rows after one original ADD sequence. */
    int visitRegistrationsAfter(
            long afterSequence,
            int limit,
            Consumer<ActiveRegistrations.Registration> visitor);

    /** Visits the maintained unique-team set for the active WP. */
    int visitRegisteredTeams(Consumer<TeamId> visitor);

    /** Visits live Central RaceData in one ordered node-state read. */
    void visitRaceData(
            BiConsumer<RegistrationId, TeamId> assignmentVisitor,
            BiConsumer<RegistrationId, Long> stageStartVisitor);

    /** Metadata captured from one caught-up active-WP projection. */
    final class RegistrationProjectionInfo {
        private final LocationId locationId;
        private final int count;
        private final int uniqueCount;
        private final long lastMutationSequence;
        private final long lastRevokeSequence;

        /** Captures projection identity, size and mutation cursors from one ordered read. */
        RegistrationProjectionInfo(
                LocationId locationId,
                int count,
                int uniqueCount,
                long lastMutationSequence,
                long lastRevokeSequence) {

            this.locationId = locationId;
            this.count = count;
            this.uniqueCount = uniqueCount;
            this.lastMutationSequence = lastMutationSequence;
            this.lastRevokeSequence = lastRevokeSequence;
        }

        /** Identifies the OPEN location represented by this projection. */
        public LocationId locationId() {

            return locationId;
        }

        /** Number of retained chronological registration rows. */
        public int count() {

            return count;
        }

        /** Number of unique teams represented by valid active registrations. */
        public int uniqueCount() {

            return uniqueCount;
        }

        /** Latest LogBook sequence that changed registration membership or validity. */
        public long lastMutationSequence() {

            return lastMutationSequence;
        }

        /** Latest LogBook sequence that successfully invalidated a registration row. */
        public long lastRevokeSequence() {

            return lastRevokeSequence;
        }
    }
}
