package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Keeps the registration history in original sequence order, including revoked ADD records.
 *
 * <p>An ADD contributes one display row. A matching REV marks exactly one row invalid
 * without changing its sequence or position. Unique-team accounting belongs to RegisteredTeams.</p>
 */
public final class ActiveRegistrations {
    private final List<Registration> registrations = new ArrayList<>();

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
            TimingData.RegistrationAction action = registrationAction(data);
            if (action == TimingData.RegistrationAction.ADD) {
                registrations.add(new Registration(data, registrationId));
                change = new Change(Change.Action.ADDED, data, registrationId);
            } else if (action == TimingData.RegistrationAction.REV) {
                change = revoke(data, registrationId);
            }
        }
        return change;
    }

    /** Visits up to limit registrations of one WP in source order, including invalid rows. */
    public int visit(LocationId locationId, int limit, Consumer<Registration> visitor) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(limit >= 0, "limit must not be negative");
        failIfInvalidArgument(visitor != null, "visitor must not be null");

        int visited = 0;
        for (Registration registration : registrations) {
            if (visited >= limit) {
                break;
            }
            if (locationId.equals(registration.locationId())) {
                visitor.accept(registration);
                visited++;
            }
        }
        return visited;
    }

    /** Returns the ordered read-only list view for internal same-lane calculations. */
    public Iterable<Registration> values() {

        return Collections.unmodifiableList(registrations);
    }

    /** Revokes the newest still-valid ADD matching identity, WP and effective timestamp. */
    private Change revoke(TimingData data, RegistrationId registrationId) {

        Registration match = null;
        for (int index = registrations.size() - 1; index >= 0 && match == null; index--) {
            Registration registration = registrations.get(index);
            if (registration.isValid()
                    && registration.registrationId.equals(registrationId)
                    && Objects.equals(registration.locationId(), data.locationId())
                    && registration.data.effectiveTime().equals(data.effectiveTime())) {
                match = registration;
            }
        }

        Change change = null;
        if (match != null) {
            match.valid = false;
            change = new Change(Change.Action.REMOVED, match.data, match.registrationId);
        }
        return change;
    }

    /** Returns the identity of an automatic or manual registration, otherwise null. */
    public static RegistrationId registrationIdOrNull(TimingData data) {

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

    /** One display registration, whose validity may change after a later REV. */
    public static final class Registration {
        private final TimingData data;
        private final RegistrationId registrationId;
        private boolean valid = true;

        private Registration(TimingData data, RegistrationId registrationId) {

            this.data = data;
            this.registrationId = registrationId;
        }

        public TimingData data() {
            return data;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }

        public LocationId locationId() {
            return data.locationId();
        }

        public long sequenceNumber() {
            return data.sequenceNumber();
        }

        public boolean isValid() {
            return valid;
        }
    }

    /** An actual ADD or matched REV for downstream registered-team accounting. */
    public static final class Change {
        public enum Action {
            ADDED,
            REMOVED
        }

        private final Action action;
        private final TimingData registration;
        private final RegistrationId registrationId;

        private Change(Action action, TimingData registration, RegistrationId registrationId) {

            this.action = action;
            this.registration = registration;
            this.registrationId = registrationId;
        }

        public Action action() {
            return action;
        }

        public TimingData registration() {
            return registration;
        }

        public RegistrationId registrationId() {
            return registrationId;
        }
    }
}
