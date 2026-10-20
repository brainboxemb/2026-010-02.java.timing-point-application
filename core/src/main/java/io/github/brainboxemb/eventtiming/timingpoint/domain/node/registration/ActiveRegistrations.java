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
 * Retains chronological registration state for the currently open WP only.
 *
 * <p>The object is inactive while its TimingNode is CLOSED. Opening a WP activates one
 * LocationId, clears the previous projection and lets DataTask replay the complete LogBook from
 * sequence one. Only registration ADD/REV records for that active LocationId are applied. Older
 * registrations from earlier OPEN periods of the same WP therefore return when that WP is opened
 * again; registrations belonging to other WPs never enter this projection.</p>
 *
 * <p>Each matching ADD is copied into one compact primitive-backed row and the source TimingData
 * object is not retained. Rows remain in original ADD sequence order. A matching REV searches
 * newest-to-oldest and marks the newest still-valid row with the same RegistrationId and effective
 * timestamp invalid without removing or reordering it.</p>
 *
 * <p>The bounded visitor is the chronological smart-display read boundary. LocationId is state of
 * the projection as a whole, so rows and read calls do not repeat it. The LogBook remains the full
 * historical authority; this class owns no persistence, team assignment, CAN transport or display
 * synchronization cursor.</p>
 */
public final class ActiveRegistrations {
    private final List<Registration> registrations = new ArrayList<>();
    private int locationId;

    /** Activates a fresh projection for one open WP before LogBook reconstruction starts. */
    public void activate(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        this.locationId = locationId.value();
        registrations.clear();
    }

    /** Deactivates the projection and discards all derived rows. */
    public void deactivate() {

        locationId = 0;
        registrations.clear();
    }

    /**
     * Applies one committed TimingData record while the WP projection is active.
     *
     * <p>Registration records for other LocationIds, non-registration records and unmatched REV
     * records leave this projection unchanged.</p>
     */
    public Change apply(TimingData data) {

        failIfInvalidArgument(data != null, "data must not be null");
        failIfIllegalState(locationId != 0, "registration projection is not active");

        Change change = null;
        RegistrationId registrationId = registrationIdOrNull(data);
        if (registrationId != null && data.locationId().value() == locationId) {
            TimingTimestamp effectiveTime = data.effectiveTime();
            failIfIllegalState(effectiveTime.isAbsolute(), "committed registration time must be absolute");

            int packedRegistrationId = registrationId.packedValue();
            long effectiveTimeMillis = effectiveTime.instant().toEpochMilli();
            TimingData.RegistrationAction action = registrationAction(data);

            if (action == TimingData.RegistrationAction.ADD) {
                Registration registration =
                        new Registration(
                                data.sequenceNumber(),
                                effectiveTimeMillis,
                                packedRegistrationId);
                registrations.add(registration);
                change = new Change(Change.Action.ADDED, registration);
            } else if (action == TimingData.RegistrationAction.REV) {
                change = revoke(packedRegistrationId, effectiveTimeMillis);
            }
        }
        return change;
    }

    /**
     * Visits at most {@code limit} rows in original ADD sequence order, including invalid rows.
     *
     * <p>The visitor executes synchronously during the NodeService read turn and must remain short
     * and non-blocking.</p>
     */
    public int visit(int limit, Consumer<Registration> visitor) {

        return visitAfter(0L, limit, visitor);
    }

    /**
     * Visits rows after one original ADD sequence for display paging.
     *
     * <p>A later REV may invalidate an older row, so a display that tracks only newer sequence
     * numbers must separately revisit already shown rows when checking validity.</p>
     */
    public int visitAfter(
            long afterSequence,
            int limit,
            Consumer<Registration> visitor) {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        failIfInvalidArgument(afterSequence >= 0L, "afterSequence must not be negative");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        int visited = 0;
        for (Registration registration : registrations) {
            if (visited >= limit) {
                break;
            }
            if (registration.sequenceNumber > afterSequence) {
                visitor.accept(registration);
                visited++;
            }
        }
        return visited;
    }

    /** Revokes the newest still-valid ADD matching identity and effective timestamp. */
    private Change revoke(
            int registrationId,
            long effectiveTimeMillis) {

        Registration match = null;
        for (int index = registrations.size() - 1; index >= 0 && match == null; index--) {
            Registration registration = registrations.get(index);
            if (registration.valid
                    && registration.registrationId == registrationId
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
     * One retained chronological registration row for the active WP.
     *
     * <p>LocationId is not stored per row because every row belongs to the same active projection.
     * Semantic RegistrationId and TimingTimestamp values are reconstructed only at the read/output
     * boundary. Validity is the only mutable field.</p>
     */
    public static final class Registration {
        private final long sequenceNumber;
        private final long effectiveTimeMillis;
        private final int registrationId;
        private boolean valid = true;

        private Registration(
                long sequenceNumber,
                long effectiveTimeMillis,
                int registrationId) {

            this.sequenceNumber = sequenceNumber;
            this.effectiveTimeMillis = effectiveTimeMillis;
            this.registrationId = registrationId;
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

        /** Returns whether this ADD row is still valid after all applied REV records. */
        public boolean isValid() {

            return valid;
        }
    }

    /**
     * One actual validity transition consumed by RegisteredTeams.
     *
     * <p>ADDED identifies a newly appended valid row; REMOVED identifies the retained row that a
     * matching REV just invalidated.</p>
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
    }
}
