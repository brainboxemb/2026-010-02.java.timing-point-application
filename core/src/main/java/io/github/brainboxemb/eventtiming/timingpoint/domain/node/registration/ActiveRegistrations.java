package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Maintains the display-ready registration list for the currently open WP.
 *
 * <p>The LogBook remains historical authority. OPEN activates one LocationId and DataTask replays
 * the complete LogBook into this projection. Matching ADD rows remain in original sequence order;
 * a matching REV marks the newest corresponding row invalid without removing or reordering it.</p>
 *
 * <p>Each retained row is compact primitive state containing the registration passage time,
 * current RegistrationId-scoped stage start and current competition rank. Runtime is derived by
 * direct subtraction. A stage-start SET/DEL updates every retained passage for that RegistrationId
 * and recalculates ranking. Valid rows with known stage starts participate independently; equal
 * runtimes share competition rank. Invalid or start-less rows have rank zero.</p>
 *
 * <p>Historical rebuild defers ranking until catch-up completes, avoiding repeated whole-list
 * ranking work while LogBook rows are replayed. Later live ADD/REV and RaceData stage-start
 * changes update ranking immediately on the NodeService Task Lane.</p>
 */
public final class ActiveRegistrations {
    private static final long NO_STAGE_START = Long.MIN_VALUE;

    private final List<Registration> registrations = new ArrayList<Registration>();
    private long[] rankingElapsedMillis = new long[0];

    private int locationId;
    private boolean rebuilding;
    private long lastMutationSequence;
    private long lastRevokeSequence;

    /** Activates an empty projection and starts one silent LogBook reconstruction. */
    public void activate(LocationId locationId) {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        this.locationId = locationId.value();
        registrations.clear();
        rebuilding = true;
        lastMutationSequence = 0L;
        lastRevokeSequence = 0L;
    }

    /** Finalizes one reconstructed list and calculates its current ranks once. */
    public void completeRebuild() {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        recalculateRanks();
        rebuilding = false;
    }

    /** Deactivates the projection and discards all derived rows. */
    public void deactivate() {

        locationId = 0;
        registrations.clear();
        rebuilding = false;
        lastMutationSequence = 0L;
        lastRevokeSequence = 0L;
    }

    /** Applies one committed TimingData record without a currently known stage start. */
    public Change apply(TimingRecord record) {

        return apply(record, null);
    }

    /**
     * Applies one committed TimingData record and the current stage start for a new ADD.
     *
     * <p>Registration records for other LocationIds, non-registration records and unmatched REV
     * records leave this projection unchanged. During reconstruction ranking is deferred until
     * completeRebuild(); live changes recalculate it immediately.</p>
     */
    public Change apply(
            TimingRecord record,
            Long stageStartMillis) {

        failIfInvalidArgument(record != null, "record must not be null");
        failIfIllegalState(locationId != 0, "registration projection is not active");

        Change change = null;
        if (isRegistration(record) && record.locationId() == locationId) {
            int packedRegistrationId = record.registrationId();
            long effectiveTimeMillis = record.effectiveTimeMillis();

            if (record.action() == TimingRecord.Action.ADD) {
                Registration registration =
                        new Registration(
                                record.sequenceNumber(),
                                effectiveTimeMillis,
                                packedRegistrationId,
                                stageStartMillis == null
                                        ? NO_STAGE_START
                                        : stageStartMillis.longValue());
                registrations.add(registration);
                lastMutationSequence = record.sequenceNumber();
                change = new Change(Change.Action.ADDED, registration);
            } else if (record.action() == TimingRecord.Action.REV) {
                change = revoke(packedRegistrationId, effectiveTimeMillis);
                if (change != null) {
                    lastMutationSequence = record.sequenceNumber();
                    lastRevokeSequence = record.sequenceNumber();
                }
            }
        }

        if (change != null && !rebuilding) {
            recalculateRanks();
        }
        return change;
    }

    /**
     * Applies normalized RegistrationId-scoped stage-start changes to retained display rows.
     *
     * <p>Rows not yet replayed during reconstruction will receive the current RaceData value when
     * their ADD is applied. Ranking is recalculated once for the complete change set when live.</p>
     */
    public void applyStageStartChanges(
            Map<RegistrationId, Long> startsToSet,
            List<RegistrationId> startsToDelete) {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        failIfInvalidArgument(startsToSet != null, "startsToSet must not be null");
        failIfInvalidArgument(startsToDelete != null, "startsToDelete must not be null");

        boolean changed = false;
        for (Map.Entry<RegistrationId, Long> entry : startsToSet.entrySet()) {
            RegistrationId registrationId = entry.getKey();
            Long startMillis = entry.getValue();
            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            failIfInvalidArgument(startMillis != null, "stage start must not be null");
            changed |= replaceStageStart(
                    registrationId.packedValue(),
                    startMillis.longValue());
        }

        for (RegistrationId registrationId : startsToDelete) {
            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            changed |= replaceStageStart(
                    registrationId.packedValue(),
                    NO_STAGE_START);
        }

        if (changed && !rebuilding) {
            recalculateRanks();
        }
    }

    /** Applies one engineering stage-start update to all passages of one RegistrationId. */
    public void stageStartChanged(
            RegistrationId registrationId,
            Long startMillis) {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");

        long value =
                startMillis == null
                        ? NO_STAGE_START
                        : startMillis.longValue();
        if (replaceStageStart(registrationId.packedValue(), value)
                && !rebuilding) {
            recalculateRanks();
        }
    }

    /** Returns the active LocationId. */
    public LocationId locationId() {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        return new LocationId(locationId);
    }

    /** Returns the number of chronological ADD rows retained for the active WP. */
    public int size() {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        return registrations.size();
    }

    /** Returns the last LogBook sequence that changed row membership/validity, or zero when empty. */
    public long lastMutationSequence() {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        return lastMutationSequence;
    }

    /** Returns the last successful REV sequence, or zero when no row was invalidated. */
    public long lastRevokeSequence() {

        failIfIllegalState(locationId != 0, "registration projection is not active");
        return lastRevokeSequence;
    }

    /** Visits at most limit rows in original ADD sequence order, including invalid rows. */
    public int visit(
            int limit,
            Consumer<Registration> visitor) {

        return visitAfter(0L, limit, visitor);
    }

    /** Visits rows after one original ADD sequence for display paging. */
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

    /** Updates the retained stage-start scalar on every passage with one packed RegistrationId. */
    private boolean replaceStageStart(
            int registrationId,
            long stageStartMillis) {

        boolean changed = false;
        for (Registration registration : registrations) {
            if (registration.registrationId == registrationId
                    && registration.stageStartMillis != stageStartMillis) {
                registration.stageStartMillis = stageStartMillis;
                changed = true;
            }
        }
        return changed;
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

    /**
     * Recalculates competition rank from compact primitive elapsed times.
     *
     * <p>The reusable primitive buffer avoids a retained duplicate ranking model. Sorting at most
     * a few thousand long values is bounded display-projection work and keeps equal elapsed times
     * on the same rank: rank = one plus the number of strictly faster valid passages.</p>
     */
    private void recalculateRanks() {

        ensureRankingCapacity(registrations.size());

        int rankableCount = 0;
        for (Registration registration : registrations) {
            registration.rank = 0;
            if (registration.valid && registration.hasStageStart()) {
                rankingElapsedMillis[rankableCount++] =
                        registration.elapsedMillis();
            }
        }

        Arrays.sort(rankingElapsedMillis, 0, rankableCount);

        for (Registration registration : registrations) {
            if (registration.valid && registration.hasStageStart()) {
                registration.rank =
                        firstIndexOf(
                                rankingElapsedMillis,
                                rankableCount,
                                registration.elapsedMillis())
                                + 1;
            }
        }
    }

    /** Grows the reusable primitive ranking buffer only when the retained list grows. */
    private void ensureRankingCapacity(int required) {

        if (rankingElapsedMillis.length >= required) {
            return;
        }

        int grown = Math.max(required, Math.max(16, rankingElapsedMillis.length * 2));
        rankingElapsedMillis = Arrays.copyOf(rankingElapsedMillis, grown);
    }

    /** Returns the first index containing value in one sorted prefix. */
    private static int firstIndexOf(
            long[] values,
            int length,
            long value) {

        int low = 0;
        int high = length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /** Reports whether this record changes registration state. */
    private static boolean isRegistration(TimingRecord record) {

        return record.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION
                || record.kind() == TimingRecord.Kind.MANUAL_REGISTRATION;
    }

    /**
     * One retained display registration for the active WP.
     *
     * <p>All retained fields are primitive. RegistrationId is reconstructed only at a read
     * boundary. A missing stage start uses an internal sentinel; rank zero means the row is not
     * currently rankable because it is invalid or has no stage start.</p>
     */
    public static final class Registration {
        private final long sequenceNumber;
        private final long effectiveTimeMillis;
        private final int registrationId;
        private long stageStartMillis;
        private int rank;
        private boolean valid = true;

        private Registration(
                long sequenceNumber,
                long effectiveTimeMillis,
                int registrationId,
                long stageStartMillis) {

            this.sequenceNumber = sequenceNumber;
            this.effectiveTimeMillis = effectiveTimeMillis;
            this.registrationId = registrationId;
            this.stageStartMillis = stageStartMillis;
        }

        /** Returns the original committed ADD sequence number used for display paging. */
        public long sequenceNumber() {

            return sequenceNumber;
        }

        /** Returns the absolute effective registration time as UTC epoch milliseconds. */
        public long effectiveTimeMillis() {

            return effectiveTimeMillis;
        }

        /** Returns the registration identity reconstructed from its compact packed value. */
        public RegistrationId registrationId() {

            return RegistrationId.fromPackedValue(registrationId);
        }

        /** Reports whether this row currently has a RegistrationId-scoped stage start. */
        public boolean hasStageStart() {

            return stageStartMillis != NO_STAGE_START;
        }

        /** Returns the absolute stage start; requires hasStageStart(). */
        public long stageStartMillis() {

            failIfIllegalState(hasStageStart(), "registration has no stage start");
            return stageStartMillis;
        }

        /** Returns passage runtime in milliseconds; requires hasStageStart(). */
        public long elapsedMillis() {

            failIfIllegalState(hasStageStart(), "registration has no stage start");
            return effectiveTimeMillis - stageStartMillis;
        }

        /** Returns competition rank, or zero when this row is not currently rankable. */
        public int rank() {

            return rank;
        }

        /** Returns whether this ADD row is still valid after all applied REV records. */
        public boolean isValid() {

            return valid;
        }
    }

    /** One actual validity transition consumed by RegisteredTeams. */
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
