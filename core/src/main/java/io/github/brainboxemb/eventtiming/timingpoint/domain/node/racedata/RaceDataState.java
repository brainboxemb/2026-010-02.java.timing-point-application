package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Node-local current race/reference data, owned exclusively by the Task Lane.
 *
 * <p>Each RACE_DATA message is validated on the Task Lane against copies of the current data.
 * Both sets are replaced only if the whole message is valid, before the
 * upstream protocol can acknowledge the accepted sequence. This component is
 * passive; it owns neither an executor nor an upstream transport.</p>
 */
public final class RaceDataState {
    private final EventData eventData;
    private final TeamAssignments teamAssignments = new TeamAssignments();
    private final StageStartTimes stageStartTimes = new StageStartTimes();

    public RaceDataState(EventData eventData) {
        checkArgument(eventData != null, "eventData must not be null");
        this.eventData = eventData;
    }

    /** Internal collaborators used by the node's derived timing view. */
    public TeamAssignments teamAssignments() {
        return teamAssignments;
    }

    public StageStartTimes stageStartTimes() {
        return stageStartTimes;
    }

    public TeamId teamIdFor(RegistrationId registrationId) {
        checkArgument(registrationId != null, "registrationId must not be null");
        TeamId fixed = eventData.teamIdFor(registrationId);
        return fixed != null ? fixed : teamAssignments.teamIdFor(registrationId);
    }

    public TimingTimestamp stageStartTimeFor(TeamId teamId) {
        return stageStartTimes.startFor(teamId);
    }

    public void applyChanges(UpstreamData.RaceData changes) {
        checkArgument(changes != null, "raceData must not be null");

        TeamAssignments updatedAssignments = teamAssignments.copy();
        StageStartTimes updatedStarts = stageStartTimes.copy();
        applyTeamAssignmentChanges(updatedAssignments, changes);
        applyStageStartChanges(updatedStarts, changes);
        updatedAssignments.validate();

        teamAssignments.replaceWith(updatedAssignments);
        stageStartTimes.replaceWith(updatedStarts);
    }

    private void applyTeamAssignmentChanges(
            TeamAssignments updatedAssignments, UpstreamData.RaceData changes) {
        checkArgument(changes.teamAssignmentChanges() != null,
                "team assignment changes must not be null");

        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            checkArgument(change != null, "team assignment change must not be null");
            switch (change.operation()) {
                case SET:
                    checkArgument(eventData.teamIdFor(change.registrationId()) == null,
                            "active assignment cannot override fixed EventData TeamId for %s",
                            change.registrationId());
                    updatedAssignments.assign(change.registrationId(), change.teamId());
                    break;
                case DEL:
                    updatedAssignments.remove(change.registrationId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported team assignment operation " + change.operation());
            }
        }
    }

    private static void applyStageStartChanges(
            StageStartTimes updatedStarts, UpstreamData.RaceData changes) {
        checkArgument(changes.stageStartTimeChanges() != null,
                "stage start changes must not be null");

        for (UpstreamData.StageStartTimeChange change : changes.stageStartTimeChanges()) {
            checkArgument(change != null, "stage start change must not be null");
            switch (change.operation()) {
                case SET:
                    updatedStarts.set(change.teamId(), change.start());
                    break;
                case DEL:
                    updatedStarts.remove(change.teamId());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "unsupported stage start operation " + change.operation());
            }
        }
    }
}
