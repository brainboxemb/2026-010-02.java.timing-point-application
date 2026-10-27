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
 * serial Task Lane. Duplicate changes for one RegistrationId are canonicalized without Maps or
 * Lists. A delete wins over a set, matching the previous normalized apply semantics.</p>
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

    public RaceDataUpdate(int capacity) {
        failIfInvalidArgument(capacity > 0, "capacity must be positive");
        assignmentSetIds = new RegistrationId[capacity];
        assignmentSetTeams = new TeamId[capacity];
        assignmentDeleteIds = new RegistrationId[capacity];
        stageStartSetIds = new RegistrationId[capacity];
        stageStartSetValues = new long[capacity];
        stageStartDeleteIds = new RegistrationId[capacity];
    }

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

    public void setTeamAssignment(RegistrationId registrationId, TeamId teamId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(teamId != null, "teamId must not be null");
        if (indexOf(assignmentDeleteIds, assignmentDeleteCount, registrationId) >= 0) return;
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

    public void deleteTeamAssignment(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        int setIndex = indexOf(assignmentSetIds, assignmentSetCount, registrationId);
        if (setIndex >= 0) removeAssignmentSet(setIndex);
        if (indexOf(assignmentDeleteIds, assignmentDeleteCount, registrationId) < 0) {
            requireCapacity(assignmentDeleteCount, assignmentDeleteIds.length);
            assignmentDeleteIds[assignmentDeleteCount++] = registrationId;
        }
    }

    public void setStageStart(RegistrationId registrationId, long stageStart) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        if (indexOf(stageStartDeleteIds, stageStartDeleteCount, registrationId) >= 0) return;
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

    public void deleteStageStart(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        int setIndex = indexOf(stageStartSetIds, stageStartSetCount, registrationId);
        if (setIndex >= 0) removeStageStartSet(setIndex);
        if (indexOf(stageStartDeleteIds, stageStartDeleteCount, registrationId) < 0) {
            requireCapacity(stageStartDeleteCount, stageStartDeleteIds.length);
            stageStartDeleteIds[stageStartDeleteCount++] = registrationId;
        }
    }

    public int teamAssignmentSetCount() { return assignmentSetCount; }
    public RegistrationId teamAssignmentSetRegistrationId(int index) {
        requireIndex(index, assignmentSetCount); return assignmentSetIds[index];
    }
    public TeamId teamAssignmentSetTeamId(int index) {
        requireIndex(index, assignmentSetCount); return assignmentSetTeams[index];
    }
    public int teamAssignmentDeleteCount() { return assignmentDeleteCount; }
    public RegistrationId teamAssignmentDeleteRegistrationId(int index) {
        requireIndex(index, assignmentDeleteCount); return assignmentDeleteIds[index];
    }
    public int stageStartSetCount() { return stageStartSetCount; }
    public RegistrationId stageStartSetRegistrationId(int index) {
        requireIndex(index, stageStartSetCount); return stageStartSetIds[index];
    }
    public long stageStartSetValue(int index) {
        requireIndex(index, stageStartSetCount); return stageStartSetValues[index];
    }
    public int stageStartDeleteCount() { return stageStartDeleteCount; }
    public RegistrationId stageStartDeleteRegistrationId(int index) {
        requireIndex(index, stageStartDeleteCount); return stageStartDeleteIds[index];
    }

    boolean releasesTeam(RegistrationId registrationId, TeamId teamId) {
        if (indexOf(assignmentDeleteIds, assignmentDeleteCount, registrationId) >= 0) return true;
        int index = indexOf(assignmentSetIds, assignmentSetCount, registrationId);
        return index >= 0 && !assignmentSetTeams[index].equals(teamId);
    }

    private void removeAssignmentSet(int index) {
        int moved = assignmentSetCount - index - 1;
        if (moved > 0) {
            System.arraycopy(assignmentSetIds, index + 1, assignmentSetIds, index, moved);
            System.arraycopy(assignmentSetTeams, index + 1, assignmentSetTeams, index, moved);
        }
        assignmentSetIds[--assignmentSetCount] = null;
        assignmentSetTeams[assignmentSetCount] = null;
    }

    private void removeStageStartSet(int index) {
        int moved = stageStartSetCount - index - 1;
        if (moved > 0) {
            System.arraycopy(stageStartSetIds, index + 1, stageStartSetIds, index, moved);
            System.arraycopy(stageStartSetValues, index + 1, stageStartSetValues, index, moved);
        }
        stageStartSetIds[--stageStartSetCount] = null;
    }

    private static int indexOf(RegistrationId[] values, int length, RegistrationId registrationId) {
        for (int index = 0; index < length; index++) {
            if (values[index].equals(registrationId)) return index;
        }
        return -1;
    }

    private static void requireCapacity(int count, int capacity) {
        failIfIllegalState(count < capacity, "RaceDataUpdate capacity exceeded");
    }

    private static void requireIndex(int index, int count) {
        failIfInvalidArgument(index >= 0 && index < count, "index out of range: %s", index);
    }
}
