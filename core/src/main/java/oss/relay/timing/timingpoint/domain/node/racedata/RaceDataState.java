package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.function.BiConsumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Retains current Central-owned race data for one TimingNode on the NodeService Task Lane. */
public final class RaceDataState {
    private final EventData eventData;
    private final TeamAssignments teamAssignments = new TeamAssignments();
    private final StageStartTimes stageStartTimes = new StageStartTimes();
    private long stageStartRevision;

    public RaceDataState(EventData eventData) {
        failIfInvalidArgument(eventData != null, "eventData must not be null");
        this.eventData = eventData;
    }

    public void setStageStart(RegistrationId registrationId, long stageStart) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        if (stageStartTimes.set(registrationId, stageStart)) stageStartRevision++;
    }

    public Long stageStartFor(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return stageStartTimes.startFor(registrationId);
    }

    public int visitLiveAssignments(BiConsumer<RegistrationId, TeamId> visitor) {
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return teamAssignments.visit(visitor);
    }

    public int visitStageStarts(BiConsumer<RegistrationId, Long> visitor) {
        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return stageStartTimes.visit(visitor);
    }

    public long stageStartRevision() { return stageStartRevision; }

    public TeamId teamIdFor(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        TeamId configuredTeamId = eventData.teamIdFor(registrationId);
        return configuredTeamId != null ? configuredTeamId : teamAssignments.teamIdFor(registrationId);
    }

    /**
     * Validates and atomically applies one normalized bounded RACE_DATA transaction.
     *
     * <p>Validation completes before mutation. No retained-state copy or temporary collection is
     * created for the transaction.</p>
     */
    public void applyChanges(RaceDataUpdate changes) {
        failIfInvalidArgument(changes != null, "changes must not be null");
        validateTeamAssignmentChanges(changes);

        for (int index = 0; index < changes.teamAssignmentSetCount(); index++) {
            teamAssignments.assign(
                    changes.teamAssignmentSetRegistrationId(index),
                    changes.teamAssignmentSetTeamId(index));
        }
        for (int index = 0; index < changes.teamAssignmentDeleteCount(); index++) {
            teamAssignments.remove(changes.teamAssignmentDeleteRegistrationId(index));
        }

        boolean stageStartsChanged = false;
        for (int index = 0; index < changes.stageStartSetCount(); index++) {
            stageStartsChanged |= stageStartTimes.set(
                    changes.stageStartSetRegistrationId(index),
                    changes.stageStartSetValue(index));
        }
        for (int index = 0; index < changes.stageStartDeleteCount(); index++) {
            stageStartsChanged |= stageStartTimes.remove(
                    changes.stageStartDeleteRegistrationId(index));
        }
        if (stageStartsChanged) stageStartRevision++;
    }

    private void validateTeamAssignmentChanges(RaceDataUpdate changes) {
        for (int index = 0; index < changes.teamAssignmentSetCount(); index++) {
            RegistrationId registrationId = changes.teamAssignmentSetRegistrationId(index);
            TeamId teamId = changes.teamAssignmentSetTeamId(index);

            failIfInvalidArgument(
                    eventData.teamIdFor(registrationId) == null,
                    "active assignment cannot override fixed EventData TeamId for %s",
                    registrationId);

            for (int other = index + 1; other < changes.teamAssignmentSetCount(); other++) {
                failIfInvalidArgument(
                        !teamId.equals(changes.teamAssignmentSetTeamId(other)),
                        "one TeamId may not be assigned to multiple registrations: %s",
                        teamId);
            }

            RegistrationId currentOwner = teamAssignments.registrationIdFor(teamId);
            failIfInvalidArgument(
                    currentOwner == null
                            || currentOwner.equals(registrationId)
                            || changes.releasesTeam(currentOwner, teamId),
                    "one TeamId may not be assigned to multiple registrations: %s",
                    teamId);
        }
    }
}
