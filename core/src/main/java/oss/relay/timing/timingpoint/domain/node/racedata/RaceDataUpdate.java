package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.Arrays;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Reusable bounded working transaction for one normalized RACE_DATA message.
 *
 * <p>The owning NodeService allocates one instance at composition time and reuses it on its
 * serial Task Lane. The fixed arrays are sized from the IF-06 message bound, so processing a new
 * message does not allocate another collection. clear() releases retained object references before
 * the buffer is reused.</p>
 *
 * <p>Duplicate SET changes for one RegistrationId collapse to the final value. A DEL is retained
 * separately and wins over SET regardless of source ordering, matching the previous normalized
 * Map-plus-delete-list apply semantics.</p>
 *
 * <p>This class is working transaction state, not authoritative race data. RaceDataState validates
 * the complete canonicalized transaction and only then mutates retained TeamAssignments and
 * StageStartTimes.</p>
 */
public final class RaceDataUpdate {
    private final RegistrationId[] assignmentSetIds;
    private final TeamId[] assignmentSetTeams;
    private final RegistrationId[] assignmentDeleteIds;
    private final RegistrationId[] stageStartSetIds;
    private final long[] stageStartSetValues;
    private final RegistrationId[] stageStartDeleteIds;

    private int assignmentSetCount;
    private int assignmentDeleteCount;
    private int stageStartSetCount;
    private int stageStartDeleteCount;

    /** Allocates the fixed working arrays used for every later transaction. */
    public RaceDataUpdate(int capacity) {

        failIfInvalidArgument(capacity > 0, "capacity must be positive");
        assignmentSetIds = new RegistrationId[capacity];
        assignmentSetTeams = new TeamId[capacity];
        assignmentDeleteIds = new RegistrationId[capacity];
        stageStartSetIds = new RegistrationId[capacity];
        stageStartSetValues = new long[capacity];
        stageStartDeleteIds = new RegistrationId[capacity];
    }

    /** Releases references from the previous transaction and resets all logical counts to zero. */
    public void clear() {

        Arrays.fill(assignmentSetIds, 0, assignmentSetCount, null);
        Arrays.fill(assignmentSetTeams, 0, assignmentSetCount, null);
        Arrays.fill(assignmentDeleteIds, 0, assignmentDeleteCount, null);
        Arrays.fill(stageStartSetIds, 0, stageStartSetCount, null);
        Arrays.fill(stageStartDeleteIds, 0, stageStartDeleteCount, null);
        assignmentSetCount = 0;
        assignmentDeleteCount = 0;
        stageStartSetCount = 0;
        stageStartDeleteCount = 0;
    }

    /** Records or replaces the final SET for one assignment unless that id is deleted. */
    public void setTeamAssignment(RegistrationId registrationId, TeamId teamId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        if (indexOf(assignmentDeleteIds, assignmentDeleteCount, registrationId) >= 0) {
            return;
        }

        int index = indexOf(assignmentSetIds, assignmentSetCount, registrationId);
        if (index >= 0) {
            assignmentSetTeams[index] = teamId;
            return;
        }

        requireCapacity(assignmentSetCount, assignmentSetIds.length);
        assignmentSetIds[assignmentSetCount] = registrationId;
        assignmentSetTeams[assignmentSetCount] = teamId;
        assignmentSetCount++;
    }

    /** Records DEL for one assignment and removes any SET for the same id from this transaction. */
    public void deleteTeamAssignment(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        int setIndex = indexOf(assignmentSetIds, assignmentSetCount, registrationId);
        if (setIndex >= 0) {
            removeAssignmentSet(setIndex);
        }
        if (indexOf(assignmentDeleteIds, assignmentDeleteCount, registrationId) < 0) {
            requireCapacity(assignmentDeleteCount, assignmentDeleteIds.length);
            assignmentDeleteIds[assignmentDeleteCount++] = registrationId;
        }
    }

    /** Records or replaces the final normalized stage-start SET unless that id is deleted. */
    public void setStageStart(RegistrationId registrationId, long stageStart) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        if (indexOf(stageStartDeleteIds, stageStartDeleteCount, registrationId) >= 0) {
            return;
        }

        int index = indexOf(stageStartSetIds, stageStartSetCount, registrationId);
        if (index >= 0) {
            stageStartSetValues[index] = stageStart;
            return;
        }

        requireCapacity(stageStartSetCount, stageStartSetIds.length);
        stageStartSetIds[stageStartSetCount] = registrationId;
        stageStartSetValues[stageStartSetCount] = stageStart;
        stageStartSetCount++;
    }

    /** Records DEL for one stage start and removes any SET for the same id from this transaction. */
    public void deleteStageStart(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        int setIndex = indexOf(stageStartSetIds, stageStartSetCount, registrationId);
        if (setIndex >= 0) {
            removeStageStartSet(setIndex);
        }
        if (indexOf(stageStartDeleteIds, stageStartDeleteCount, registrationId) < 0) {
            requireCapacity(stageStartDeleteCount, stageStartDeleteIds.length);
            stageStartDeleteIds[stageStartDeleteCount++] = registrationId;
        }
    }

    /** Returns the number of canonical assignment SET changes. */
    public int teamAssignmentSetCount() {

        return assignmentSetCount;
    }

    /** Returns the RegistrationId for one canonical assignment SET. */
    public RegistrationId teamAssignmentSetRegistrationId(int index) {

        requireIndex(index, assignmentSetCount);
        return assignmentSetIds[index];
    }

    /** Returns the TeamId for one canonical assignment SET. */
    public TeamId teamAssignmentSetTeamId(int index) {

        requireIndex(index, assignmentSetCount);
        return assignmentSetTeams[index];
    }

    /** Returns the number of canonical assignment DEL changes. */
    public int teamAssignmentDeleteCount() {

        return assignmentDeleteCount;
    }

    /** Returns the RegistrationId for one canonical assignment DEL. */
    public RegistrationId teamAssignmentDeleteRegistrationId(int index) {

        requireIndex(index, assignmentDeleteCount);
        return assignmentDeleteIds[index];
    }

    /** Returns the number of canonical stage-start SET changes. */
    public int stageStartSetCount() {

        return stageStartSetCount;
    }

    /** Returns the RegistrationId for one canonical stage-start SET. */
    public RegistrationId stageStartSetRegistrationId(int index) {

        requireIndex(index, stageStartSetCount);
        return stageStartSetIds[index];
    }

    /** Returns the normalized compact UTC value for one canonical stage-start SET. */
    public long stageStartSetValue(int index) {

        requireIndex(index, stageStartSetCount);
        return stageStartSetValues[index];
    }

    /** Returns the number of canonical stage-start DEL changes. */
    public int stageStartDeleteCount() {

        return stageStartDeleteCount;
    }

    /** Returns the RegistrationId for one canonical stage-start DEL. */
    public RegistrationId stageStartDeleteRegistrationId(int index) {

        requireIndex(index, stageStartDeleteCount);
        return stageStartDeleteIds[index];
    }

    /**
     * Reports whether this transaction releases a TeamId from its currently owning RegistrationId.
     *
     * <p>A delete releases the team. A SET to another TeamId also releases the previous team.
     * This lets RaceDataState validate swaps against the final transaction without first copying
     * the complete retained assignment map.</p>
     */
    boolean releasesTeam(RegistrationId registrationId, TeamId teamId) {

        if (indexOf(assignmentDeleteIds, assignmentDeleteCount, registrationId) >= 0) {
            return true;
        }
        int index = indexOf(assignmentSetIds, assignmentSetCount, registrationId);
        return index >= 0 && !assignmentSetTeams[index].equals(teamId);
    }

    /** Removes one assignment SET while preserving the order of remaining canonical changes. */
    private void removeAssignmentSet(int index) {

        int moved = assignmentSetCount - index - 1;
        if (moved > 0) {
            System.arraycopy(assignmentSetIds, index + 1, assignmentSetIds, index, moved);
            System.arraycopy(assignmentSetTeams, index + 1, assignmentSetTeams, index, moved);
        }
        assignmentSetIds[--assignmentSetCount] = null;
        assignmentSetTeams[assignmentSetCount] = null;
    }

    /** Removes one stage-start SET while preserving the order of remaining canonical changes. */
    private void removeStageStartSet(int index) {

        int moved = stageStartSetCount - index - 1;
        if (moved > 0) {
            System.arraycopy(stageStartSetIds, index + 1, stageStartSetIds, index, moved);
            System.arraycopy(stageStartSetValues, index + 1, stageStartSetValues, index, moved);
        }
        stageStartSetIds[--stageStartSetCount] = null;
    }

    /** Finds one RegistrationId in the used prefix of a fixed working array. */
    private static int indexOf(
            RegistrationId[] values,
            int length,
            RegistrationId registrationId) {

        for (int index = 0; index < length; index++) {
            if (values[index].equals(registrationId)) {
                return index;
            }
        }
        return -1;
    }

    /** Fails when one logical change group would exceed its fixed transaction capacity. */
    private static void requireCapacity(int count, int capacity) {

        failIfIllegalState(count < capacity, "RaceDataUpdate capacity exceeded");
    }

    /** Validates an indexed read against the used prefix instead of the physical array length. */
    private static void requireIndex(int index, int count) {

        failIfInvalidArgument(index >= 0 && index < count, "index out of range: %s", index);
    }
}
