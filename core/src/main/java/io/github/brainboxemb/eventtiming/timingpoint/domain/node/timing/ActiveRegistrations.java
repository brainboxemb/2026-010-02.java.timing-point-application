package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Keeps registration history in original sequence order as compact retained state.
 *
 * <p>TimingData is accepted only at the input boundary. Each ADD is copied into one
 * primitive-backed Registration and the TimingData object is not retained. A matching
 * REV marks the existing row invalid without changing its sequence or position.</p>
 */
public final class ActiveRegistrations {
    private final List<Registration> registrations = new ArrayList<>();
    private final Iterable<Registration> registrationView =
            Collections.unmodifiableList(registrations);

    /** Clears the reconstructed registration list before LogBook replay. */
    public void reset() {

        registrations.clear();
    }

    /** Applies one committed ADD or REV and reports an actual validity change. */
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

    /** Visits at most limit rows of one WP in source order, including invalid rows. */
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

    /** Returns the ordered read-only list view for internal same-lane calculations. */
    public Iterable<Registration> values() {

        return registrationView;
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
     * One retained display registration.
     *
     * <p>Only scalar state is retained. Semantic TimingData value objects are reconstructed
     * by getters when a caller actually crosses the read/output boundary.</p>
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

        public long sequenceNumber() {

            return sequenceNumber;
        }

        public TimingTimestamp effectiveTime() {

            return new TimingTimestamp(Instant.ofEpochMilli(effectiveTimeMillis));
        }

        public RegistrationId registrationId() {

            return RegistrationId.fromPackedValue(registrationId);
        }

        public LocationId locationId() {

            return new LocationId(locationId);
        }

        public boolean isValid() {

            return valid;
        }

        long effectiveTimeMillis() {

            return effectiveTimeMillis;
        }

        int locationIdValue() {

            return locationId;
        }
    }

    /** An actual ADD or matched REV for downstream registered-team accounting. */
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

        public Action action() {

            return action;
        }

        public RegistrationId registrationId() {

            return registration.registrationId();
        }

        public LocationId locationId() {

            return registration.locationId();
        }
    }
}
