package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.function.BiConsumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Retains the current Central-owned race data for one TimingNode.
 *
 * <p>This passive state object is owned exclusively by the NodeService Task Lane. NodeService
 * normalizes each ordered RACE_DATA change set at ingress into one reusable bounded RaceDataUpdate
 * and passes compact UTC stage-start values here. Time-only values remain time-only. Retained state
 * is mutated only after the complete bounded transaction validates.</p>
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
    private long stageStartRevision;

    /** Creates node-local mutable race data over immutable EventData reference data. */
    public RaceDataState(EventData eventData) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");
        this.eventData = eventData;
    }

    /** Sets one already-normalized compact UTC stage start on the Task Lane. */
    public void setStageStart(
            RegistrationId registrationId,
            long stageStart) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        if (stageStartTimes.set(registrationId, stageStart)) {
            stageStartRevision++;
        }
    }

    /** Returns the current compact UTC stage start, or null when none is known. */
    public Long stageStartFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return stageStartTimes.startFor(registrationId);
    }

    /**
     * Visits the live RACE_DATA RegistrationId-to-TeamId assignments only.
     *
     * <p>Fixed EventData mappings are intentionally excluded so diagnostics can distinguish
     * immutable reference data from Central's current live assignment state.</p>
     */
    public int visitLiveAssignments(BiConsumer<RegistrationId, TeamId> visitor) {

        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return teamAssignments.visit(visitor);
    }

    /** Visits all current RegistrationId-scoped normalized stage starts. */
    public int visitStageStarts(BiConsumer<RegistrationId, Long> visitor) {

        failIfInvalidArgument(visitor != null, "visitor must not be null");
        return stageStartTimes.visit(visitor);
    }

    /** Monotonic revision of applied RegistrationId-scoped StageStartTimes state. */
    public long stageStartRevision() {

        return stageStartRevision;
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
     * Validates and atomically applies one normalized bounded Central RACE_DATA transaction.
     *
     * <p>Stage-start values are already compact UTC. Protocol civil-time interpretation remains
     * at NodeService ingress, while missing date knowledge remains missing in retained state.</p>
     *
     * <p>Validation completes before mutation. The bounded transaction is scanned directly, so
     * applying it does not require a copy of either retained map or another temporary collection.</p>
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
            stageStartsChanged |=
                    stageStartTimes.set(
                            changes.stageStartSetRegistrationId(index),
                            changes.stageStartSetValue(index));
        }
        for (int index = 0; index < changes.stageStartDeleteCount(); index++) {
            stageStartsChanged |=
                    stageStartTimes.remove(
                            changes.stageStartDeleteRegistrationId(index));
        }
        if (stageStartsChanged) {
            stageStartRevision++;
        }
    }

    /**
     * Validates the final assignment result represented by one bounded transaction.
     *
     * <p>The transaction may release a currently owned TeamId and assign it elsewhere in the same
     * message. Validation therefore considers both retained state and all canonicalized changes
     * before any mutation is committed.</p>
     */
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
