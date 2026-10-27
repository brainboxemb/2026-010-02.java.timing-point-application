package oss.relay.timing.timingpoint.domain.node.racedata;

import oss.relay.timing.eventdata.EventData;
import oss.relay.timing.eventdata.TeamId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;

import java.util.function.BiConsumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Retains Central-owned current race state for one TimingNode.
 *
 * <p>This passive state is owned exclusively by the NodeService Task Lane. Team assignments and
 * stage starts are separate IF-06 message families and are retained independently here. Fixed
 * EventData remains authoritative for configured RegistrationId-to-TeamId mappings.</p>
 *
 * <p>RaceDataState owns no protocol timestamp interpretation, executor, packet sequence, ACK
 * behavior, registration list or presentation query model.</p>
 */
public final class RaceDataState {
    private final EventData eventData;
    private final TeamAssignments teamAssignments = new TeamAssignments();
    private final StageStartTimes stageStartTimes = new StageStartTimes();
    private long stageStartRevision;

    /** Creates node-local mutable race state over immutable EventData reference data. */
    public RaceDataState(EventData eventData) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");
        this.eventData = eventData;
    }

    /** Reports whether EventData already owns the TeamId mapping for this RegistrationId. */
    public boolean hasFixedTeamAssignment(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return eventData.teamIdFor(registrationId) != null;
    }

    /** Returns the live RegistrationId currently owning one TeamId, or null when unassigned. */
    public RegistrationId liveRegistrationIdFor(TeamId teamId) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        return teamAssignments.registrationIdFor(teamId);
    }

    /** Sets one already validated live RegistrationId-to-TeamId assignment. */
    public void assignTeam(RegistrationId registrationId, TeamId teamId) {

        teamAssignments.assign(registrationId, teamId);
    }

    /** Removes one live RegistrationId-to-TeamId assignment. */
    public void removeTeam(RegistrationId registrationId) {

        teamAssignments.remove(registrationId);
    }

    /**
     * Sets one normalized stage start without advancing the message-level revision yet.
     *
     * @return true when retained stage-start state changed
     */
    public boolean stageStartSet(
            RegistrationId registrationId,
            long stageStart) {

        return stageStartTimes.set(registrationId, stageStart);
    }

    /**
     * Removes one stage start without advancing the message-level revision yet.
     *
     * @return true when retained stage-start state changed
     */
    public boolean stageStartDeleted(RegistrationId registrationId) {

        return stageStartTimes.remove(registrationId);
    }

    /** Advances the stage-start revision once after one complete message changed retained state. */
    public void stageStartChangesApplied(boolean changed) {

        if (changed) {
            stageStartRevision++;
        }
    }

    /** Sets one engineering override and advances its retained-state revision immediately. */
    public void setStageStart(
            RegistrationId registrationId,
            long stageStart) {

        if (stageStartSet(registrationId, stageStart)) {
            stageStartRevision++;
        }
    }

    /** Returns the current compact UTC stage start, or null when none is known. */
    public Long stageStartFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        return stageStartTimes.startFor(registrationId);
    }

    /**
     * Visits live RegistrationId-to-TeamId assignments only.
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

    /** Monotonic revision of applied RegistrationId-scoped stage-start state. */
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
}
