package io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Node-local current race/reference data, owned exclusively by the Task Lane.
 *
 * <p>Each RACE_DATA message is validated on the Task Lane against copies of the current data.
 * Both sets are replaced only if the whole message is valid, before the
 * upstream protocol can acknowledge the accepted sequence. Stage-start boundary values
 * are normalized to absolute UTC while applying that message, so derived timing never
 * needs date or time-zone interpretation. This component is passive; it owns neither an
 * executor nor an upstream transport.</p>
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

    /** Internal collaborators used by the node's derived timing view. */
    public TeamAssignments teamAssignments() {

        return teamAssignments;
    }

    /** Returns the Task Lane-owned live stage-start state used by derived timing. */
    public StageStartTimes stageStartTimes() {

        return stageStartTimes;
    }

    /** Resolves TeamId from fixed EventData first, then live upstream assignment state. */
    public TeamId teamIdFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        TeamId configuredTeamId = eventData.teamIdFor(registrationId);
        return configuredTeamId != null
                ? configuredTeamId
                : teamAssignments.teamIdFor(registrationId);
    }

    /** Returns the current absolute UTC stage start, or null when none is assigned. */
    public Long stageStartTimeMillisFor(TeamId teamId) {

        return stageStartTimes.startMillisFor(teamId);
    }

    /**
     * Validates and atomically applies one complete Central RACE_DATA change set.
     *
     * <p>The supplied receive time comes from the node's shared TimeSource. A time-only
     * upstream start uses its current UTC calendar date. A date+time upstream start is
     * interpreted directly as UTC. Absolute upstream timestamps keep their Instant.</p>
     */
    public void applyChanges(UpstreamData.RaceData changes, Instant receivedAt) {

        failIfInvalidArgument(changes != null, "raceData must not be null");
        failIfInvalidArgument(receivedAt != null, "receivedAt must not be null");

        TeamAssignments updatedAssignments = teamAssignments.copy();
        StageStartTimes updatedStarts = stageStartTimes.copy();
        applyTeamAssignmentChanges(updatedAssignments, changes);
        applyStageStartChanges(updatedStarts, changes, receivedAt);
        updatedAssignments.validate();

        teamAssignments.replaceWith(updatedAssignments);
        stageStartTimes.replaceWith(updatedStarts);
    }

    /** Applies team-assignment changes to the uncommitted transactional copy. */
    private void applyTeamAssignmentChanges(
            TeamAssignments updatedAssignments, UpstreamData.RaceData changes) {

        failIfInvalidArgument(changes.teamAssignmentChanges() != null,
                "team assignment changes must not be null");

        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            failIfInvalidArgument(change != null, "team assignment change must not be null");
            switch (change.operation()) {
                case SET:
                    failIfInvalidArgument(eventData.teamIdFor(change.registrationId()) == null,
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

    /** Applies and normalizes stage-start changes to the uncommitted transactional copy. */
    private static void applyStageStartChanges(
            StageStartTimes updatedStarts,
            UpstreamData.RaceData changes,
            Instant receivedAt) {

        failIfInvalidArgument(changes.stageStartTimeChanges() != null,
                "stage start changes must not be null");

        for (UpstreamData.StageStartTimeChange change : changes.stageStartTimeChanges()) {
            failIfInvalidArgument(change != null, "stage start change must not be null");
            switch (change.operation()) {
                case SET:
                    updatedStarts.set(
                            change.teamId(),
                            absoluteStartMillis(change.start(), receivedAt));
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

    /** Converts one IF-06 stage-start boundary value to the internal UTC millisecond basis. */
    private static long absoluteStartMillis(TimingTimestamp start, Instant receivedAt) {

        failIfInvalidArgument(start != null, "stage start must not be null");

        if (start.isAbsolute()) {
            return start.instant().toEpochMilli();
        }

        LocalDate date =
                start.hasDate()
                        ? start.date()
                        : receivedAt.atOffset(ZoneOffset.UTC).toLocalDate();
        return LocalDateTime.of(date, start.time())
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli();
    }
}
