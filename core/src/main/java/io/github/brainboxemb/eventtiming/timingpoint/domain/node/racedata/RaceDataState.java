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
 * Retains the current Central-owned race data for one TimingNode.
 *
 * <p>This passive state object is owned exclusively by the Task Lane. NodeService passes each
 * ordered RACE_DATA change set here after protocol sequence handling. The message is applied to
 * independent copies of team assignments and stage starts; live state is replaced only after the
 * complete change set validates, so an invalid message cannot leave a partially applied update.</p>
 *
 * <p>Fixed EventData remains authoritative for configured RegistrationId-to-TeamId mappings.
 * Live RACE_DATA assignments may resolve only registrations that EventData does not already own,
 * and one TeamId may not be assigned to multiple live RegistrationIds.</p>
 *
 * <p>Stage-start protocol values are normalized to absolute UTC epoch milliseconds while the
 * message enters this state. Partial civil time is therefore never retained. RaceDataState owns
 * no executor, protocol sequence, ACK behavior, registration list or presentation query model.</p>
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

    /** Resolves TeamId from fixed EventData first, then live upstream assignment state. */
    public TeamId teamIdFor(RegistrationId registrationId) {

        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        TeamId configuredTeamId = eventData.teamIdFor(registrationId);
        return configuredTeamId != null
                ? configuredTeamId
                : teamAssignments.teamIdFor(registrationId);
    }

    /** Resolves RegistrationId from fixed EventData first, then live assignment state. */
    public RegistrationId registrationIdFor(TeamId teamId) {

        failIfInvalidArgument(teamId != null, "teamId must not be null");
        RegistrationId configuredRegistrationId =
                eventData.registrationIdFor(teamId);
        return configuredRegistrationId != null
                ? configuredRegistrationId
                : teamAssignments.registrationIdFor(teamId);
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
