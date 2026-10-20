package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Retains the node's chronological registration rows reconstructed from committed LogBook data.
 *
 * <p>This is Task Lane-owned derived state, not another TimingData store. DataTask applies
 * committed TimingData in sequence order. Every registration ADD is copied once into a compact
 * primitive-backed row; the TimingData object itself is not retained. Non-registration
 * TimingData is ignored.</p>
 *
 * <p>An ADD row is append-only: its sequence, WP, RegistrationId and effective time never
 * change and the row is never removed or reordered. A REV searches newest-to-oldest and marks
 * the newest still-valid row with the same RegistrationId, WP and effective timestamp invalid.
 * Keeping invalid rows preserves the chronological display list and lets a display observe that
 * a previously shown row was revoked.</p>
 *
 * <p>The bounded visit methods are the read boundary for the chronological smart-display view.
 * The display owns paging/synchronization cursors; this class owns no display state, CAN
 * encoding, team assignment, ranking or persistence.</p>
 */
public final class ActiveRegistrations {
    private final List<Registration> registrations = new ArrayList<>();

    /**
     * Clears all derived rows before DataTask replays the LogBook from sequence one.
     *
     * <p>Reset is reconstruction housekeeping; it does not represent registration revocation
     * and therefore publishes no domain change by itself.</p>
     */
    public void reset() {

        registrations.clear();
    }

    /**
     * Applies one committed TimingData record in LogBook sequence order.
     *
     * <p>Returns a Change only for an appended ADD or a REV that actually invalidated a retained
     * row. Non-registration records and unmatched REV records leave this projection unchanged.</p>
     */
    public Change apply(TimingData data) {

        failIfInvalidArgument(data != null, "data must not be null");

        Change change = null;
        RegistrationId registrationId = registrationIdOrNull(data);
        if (registrationId != null) {
            TimingTimestamp effectiveTime = data.effectiveTime();
            failIfIllegalState(effectiveTime.isAbsolute(), "committed registration time must be absolute");

            int packedRegistrationId = registrationId.packedValue();
            int locationId = data.locationId().value();
            long effectiveTimeMillis = effectiveTime.instant().toEpochMilli();
            TimingData.RegistrationAction action = registrationAction(data);

            if (action == TimingData.RegistrationAction.ADD) {
                Registration registration =
                        new Registration(
                                data.sequenceNumber(),
                                effectiveTimeMillis,
                                packedRegistrationId,
                                locationId);
                registrations.add(registration);
                change = new Change(Change.Action.ADDED, registration);
            } else if (action == TimingData.RegistrationAction.REV) {
                change = revoke(packedRegistrationId, locationId, effectiveTimeMillis);
            }
        }
        return change;
    }

    /**
     * Visits at most {@code limit} rows of one WP in original ADD sequence order.
     *
     * <p>Invalidated rows remain visible. The visitor executes synchronously during the
     * NodeService read turn and therefore must remain short and non-blocking.</p>
     */
    public int visit(LocationId locationId, int limit, Consumer<Registration> visitor) {

        return visitAfter(locationId, 0L, limit, visitor);
    }

    /**
     * Visits rows after one original ADD sequence, allowing a display to page through its list.
     *
     * <p>The display owns the cursor and what it has already shown. A later REV updates an
     * earlier row, so the display must also revisit older rows when checking validity.</p>
     */
    public int visitAfter(
            LocationId locationId,
            long afterSequence,
            int limit,
            Consumer<Registration> visitor) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        int requestedLocation = locationId.value();
        int visited = 0;
        for (Registration registration : registrations) {
            if (visited >= limit) {
                break;
            }
            if (registration.sequenceNumber > afterSequence
                    && registration.locationId == requestedLocation) {
                visitor.accept(registration);
                visited++;
            }
        }
        return visited;
    }

    /** Revokes the newest still-valid ADD matching identity, WP and effective timestamp. */
    private Change revoke(
            int registrationId,
            int locationId,
            long effectiveTimeMillis) {

        Registration match = null;
        for (int index = registrations.size() - 1; index >= 0 && match == null; index--) {
            Registration registration = registrations.get(index);
            if (registration.valid
                    && registration.registrationId == registrationId
                    && registration.locationId == locationId
                    && registration.effectiveTimeMillis == effectiveTimeMillis) {
                match = registration;
            }
        }

        Change change = null;
        if (match != null) {
            match.valid = false;
            change = new Change(Change.Action.REMOVED, match);
        }
        return change;
    }

    /** Returns the identity of an automatic or manual registration, otherwise null. */
    private static RegistrationId registrationIdOrNull(TimingData data) {

        RegistrationId registrationId = null;
        if (data instanceof TimingData.AutomaticRegistration) {
            registrationId = ((TimingData.AutomaticRegistration) data).registrationId();
        } else if (data instanceof TimingData.ManualRegistration) {
            registrationId = ((TimingData.ManualRegistration) data).registrationId();
        }
        return registrationId;
    }

    /** Returns the registration action, or null for other TimingData records. */
    private static TimingData.RegistrationAction registrationAction(TimingData data) {

        TimingData.RegistrationAction action = null;
        if (data instanceof TimingData.AutomaticRegistration) {
            action = ((TimingData.AutomaticRegistration) data).action();
        } else if (data instanceof TimingData.ManualRegistration) {
            action = ((TimingData.ManualRegistration) data).action();
        }
        return action;
    }

    /**
     * One retained chronological registration row.
     *
     * <p>The object stores only scalar state because rows can remain for the complete node
     * session. Semantic RegistrationId, LocationId and TimingTimestamp values are reconstructed
     * only when a read/output boundary asks for them. Validity is the only mutable field and may
     * change from true to false after a matching REV.</p>
     */
    public static final class Registration {
        private final long sequenceNumber;
        private final long effectiveTimeMillis;
        private final int registrationId;
        private final int locationId;
        private boolean valid = true;

        private Registration(
                long sequenceNumber,
                long effectiveTimeMillis,
                int registrationId,
                int locationId) {

            this.sequenceNumber = sequenceNumber;
            this.effectiveTimeMillis = effectiveTimeMillis;
            this.registrationId = registrationId;
            this.locationId = locationId;
        }

        /** Returns the original committed ADD sequence number used for display paging. */
        public long sequenceNumber() {

            return sequenceNumber;
        }

        /** Returns the absolute effective registration time reconstructed at the read boundary. */
        public TimingTimestamp effectiveTime() {

            return new TimingTimestamp(Instant.ofEpochMilli(effectiveTimeMillis));
        }

        /** Returns the registration identity reconstructed from its compact packed value. */
        public RegistrationId registrationId() {

            return RegistrationId.fromPackedValue(registrationId);
        }

        /** Returns the WP at which this registration row was committed. */
        public LocationId locationId() {

            return new LocationId(locationId);
        }

        /** Returns whether this ADD row is still valid after all applied REV records. */
        public boolean isValid() {

            return valid;
        }
    }

    /**
     * One actual validity transition consumed by RegisteredTeams.
     *
     * <p>ADDED identifies a newly appended valid row; REMOVED identifies the retained row that a
     * matching REV just invalidated. Unmatched REV and unrelated TimingData produce no Change.</p>
     */
    public static final class Change {
        public enum Action {
            ADDED,
            REMOVED
        }

        private final Action action;
        private final Registration registration;

        private Change(Action action, Registration registration) {

            this.action = action;
            this.registration = registration;
        }

        /** Returns whether the retained row was added or invalidated. */
        public Action action() {

            return action;
        }

        /** Returns the RegistrationId whose active occurrence count changed. */
        public RegistrationId registrationId() {

            return registration.registrationId();
        }

        /** Returns the WP whose active occurrence count changed. */
        public LocationId locationId() {

            return registration.locationId();
        }
    }
}
