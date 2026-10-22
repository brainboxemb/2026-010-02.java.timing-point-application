package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.List;
import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Retains the current Central-owned race data for one TimingNode.
 *
 * <p>This passive state object is owned exclusively by the Task Lane. NodeService normalizes each
 * ordered RACE_DATA change set at ingress and passes only domain values and absolute UTC epoch
 * milliseconds here. The changes are applied to independent copies of team assignments and stage
 * starts; live state is replaced only after the complete change set validates, so an invalid
 * message cannot leave a partially applied update.</p>
 *
 * <p>Fixed EventData remains authoritative for configured RegistrationId-to-TeamId mappings.
 * Live RACE_DATA assignments may resolve only registrations that EventData does not already own,
 * and one TeamId may not be assigned to multiple live RegistrationIds.</p>
 *
 * <p>RaceDataState owns no protocol timestamp interpretation, executor, protocol sequence,
 * ACK behavior, registration list or presentation query model.</p>
 */
public final class RaceDataState {
    private final EventData eventData;
    private final TeamAssignments teamAssignments = new TeamAssignments();
    private final StageStartTimes stageStartTimes = new StageStartTimes();

    /** Creates node-local mutable race data over immutable EventData reference data. */
    public RaceDataState(EventData eventData) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");
        this.eventData = eventData;
    }

    /** Sets one already-normalized stage-start value owned by the NodeService Task Lane. */
    public void setStageStart(
            RegistrationId registrationId,
            long startMillis) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        stageStartTimes.set(registrationId, startMillis);
    }

    /** Package-local stage-start lookup used by focused state tests. */
    Long stageStartMillisFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return stageStartTimes.startMillisFor(registrationId);
    }

    /** Resolves TeamId from fixed EventData first, then live upstream assignment state. */
    public TeamId teamIdFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        TeamId configuredTeamId = eventData.teamIdFor(registrationId);
        return configuredTeamId != null
                ? configuredTeamId
                : teamAssignments.teamIdFor(registrationId);
    }

    /**
     * Validates and atomically applies one normalized Central RACE_DATA change set.
     *
     * <p>All stage-start values are already absolute UTC epoch milliseconds. Protocol civil-time
     * interpretation therefore remains at NodeService ingress and cannot leak into retained state.</p>
     */
    public void applyChanges(
            Map<RegistrationId, TeamId> assignmentsToSet,
            List<RegistrationId> assignmentsToDelete,
            Map<RegistrationId, Long> stageStartsToSet,
            List<RegistrationId> stageStartsToDelete) {

        failIfInvalidArgument(assignmentsToSet != null, "assignmentsToSet must not be null");
        failIfInvalidArgument(assignmentsToDelete != null, "assignmentsToDelete must not be null");
        failIfInvalidArgument(stageStartsToSet != null, "stageStartsToSet must not be null");
        failIfInvalidArgument(stageStartsToDelete != null, "stageStartsToDelete must not be null");

        TeamAssignments updatedAssignments = teamAssignments.copy();
        StageStartTimes updatedStarts = stageStartTimes.copy();
        applyTeamAssignmentChanges(updatedAssignments, assignmentsToSet, assignmentsToDelete);
        applyStageStartChanges(updatedStarts, stageStartsToSet, stageStartsToDelete);
        updatedAssignments.validate();

        teamAssignments.replaceWith(updatedAssignments);
        stageStartTimes.replaceWith(updatedStarts);
    }

    /** Applies team-assignment changes to the uncommitted transactional copy. */
    private void applyTeamAssignmentChanges(
            TeamAssignments updatedAssignments,
            Map<RegistrationId, TeamId> assignmentsToSet,
            List<RegistrationId> assignmentsToDelete) {

        for (Map.Entry<RegistrationId, TeamId> change : assignmentsToSet.entrySet()) {
            RegistrationId registrationId = change.getKey();
            TeamId teamId = change.getValue();
            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            failIfInvalidArgument(teamId != null, "teamId must not be null");
            failIfInvalidArgument(eventData.teamIdFor(registrationId) == null,
                    "active assignment cannot override fixed EventData TeamId for %s",
                    registrationId);
            updatedAssignments.assign(registrationId, teamId);
        }

        for (RegistrationId registrationId : assignmentsToDelete) {
            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            updatedAssignments.remove(registrationId);
        }
    }

    /** Applies already-normalized stage-start changes to the uncommitted transactional copy. */
    private static void applyStageStartChanges(
            StageStartTimes updatedStarts,
            Map<RegistrationId, Long> stageStartsToSet,
            List<RegistrationId> stageStartsToDelete) {

        for (Map.Entry<RegistrationId, Long> change : stageStartsToSet.entrySet()) {
            RegistrationId registrationId = change.getKey();
            Long startMillis = change.getValue();
            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            failIfInvalidArgument(startMillis != null, "stage start must not be null");
            updatedStarts.set(registrationId, startMillis.longValue());
        }

        for (RegistrationId registrationId : stageStartsToDelete) {
            failIfInvalidArgument(registrationId != null, "registrationId must not be null");
            updatedStarts.remove(registrationId);
        }
    }
}
