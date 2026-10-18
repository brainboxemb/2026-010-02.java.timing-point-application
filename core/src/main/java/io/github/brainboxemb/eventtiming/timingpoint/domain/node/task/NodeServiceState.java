package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamTaskState;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Passive Task Lane-owned state behind {@link NodeService}.
 *
 * <p>This object owns mutable race/reference data and derived registration
 * state. It has no executor or lifecycle of its own. Normal state mutation
 * and consistent reads happen only while the NodeService Task Lane is current.
 * The one exception is the volatile stale marker: the Data Lane may set it when
 * a required Data-to-Task handoff could not be queued.</p>
 *
 * <p>Runtime composition creates exactly one instance and shares it between
 * {@code NodeService} and the node's upstream boundary. Upstream code sees only
 * the narrow {@code UpstreamTaskState} contract; Task Lane implementation
 * details remain package-local.</p>
 */
public final class NodeServiceState implements UpstreamTaskState {
    private final EventData eventData;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations = new ActiveRegistrations();

    private boolean derivedTimingReady;

    /**
     * Cross-lane health signal set when a required TimingData handoff is lost.
     */
    private volatile boolean derivedTimingStale;

    /** Creates the passive Task Lane state from immutable EventData reference data. */
    public NodeServiceState(EventData eventData) {
        failIfInvalidArgument(eventData != null, "eventData must not be null");
        this.eventData = eventData;
        this.raceData = new RaceDataState(eventData);
    }

    /** Returns the immutable EventData used by Task Lane derived timing. */
    EventData eventData() {
        return eventData;
    }

    /** Returns the Task Lane-owned team assignment state used by StageTiming. */
    TeamAssignments teamAssignments() {
        return raceData.teamAssignments();
    }

    /** Returns the Task Lane-owned stage start state used by StageTiming. */
    StageStartTimes stageStartTimes() {
        return raceData.stageStartTimes();
    }

    /** Marks derived timing unavailable when the owning NodeService stops. */
    void markDerivedTimingUnavailable() {
        derivedTimingReady = false;
    }

    /**
     * Marks derived timing stale after a required Data-to-Task handoff failed.
     *
     * <p>This method is intentionally safe to call from the Data Lane.</p>
     */
    void markDerivedTimingStale() {
        derivedTimingStale = true;
    }

    /** Rebuilds compact derived registration state from transient startup history. */
    void rebuildDerivedTiming(Iterable<TimingData> loadedData) {
        failIfInvalidArgument(loadedData != null, "loadedData must not be null");

        activeRegistrations.reset();

        for (TimingData data : loadedData) {
            activeRegistrations.apply(data, raceData::teamIdFor);
        }

        derivedTimingStale = false;
        derivedTimingReady = true;
    }

    /** Applies one newly committed TimingData record to derived registration state. */
    void applyCommittedTimingData(TimingData data) {
        failIfInvalidArgument(data != null, "data must not be null");
        if (!derivedTimingReady) {
            derivedTimingStale = true;
        } else {
            activeRegistrations.apply(data, raceData::teamIdFor);
        }
    }

    /** Returns compact active-registration state after availability checks. */
    ActiveRegistrations activeRegistrations() {
        ensureDerivedTimingAvailable();
        return activeRegistrations;
    }

    /** Fails when derived timing has not been initialized or became stale. */
    void ensureDerivedTimingAvailable() {
        if (!derivedTimingReady || derivedTimingStale) {
            throw new IllegalStateException("Task Lane derived timing state is not available");
        }
    }

    /** Applies Central-provided race-data changes on the Task Lane. */
    @Override
    public void applyRaceData(UpstreamData.RaceData changes) {
        raceData.applyChanges(changes);
        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            RegistrationId registrationId = change.registrationId();
            activeRegistrations.teamAssignmentChanged(
                    registrationId,
                    raceData.teamIdFor(registrationId));
        }
    }

    /** Resolves the current TeamId for one registration on the Task Lane. */
    @Override
    public TeamId teamIdFor(RegistrationId registrationId) {
        return raceData.teamIdFor(registrationId);
    }

    /** Resolves the current stage start for one team on the Task Lane. */
    @Override
    public TimingTimestamp stageStartTimeFor(TeamId teamId) {
        return raceData.stageStartTimeFor(teamId);
    }

    /** Returns the maintained unique-team count across all locations. */
    @Override
    public int uniqueTeamRegistrationCount() {
        ensureDerivedTimingAvailable();
        return activeRegistrations.uniqueTeamCount();
    }

    /** Returns the maintained number of unique teams at one location. */
    public int uniqueTeamRegistrationCount(LocationId locationId) {
        ensureDerivedTimingAvailable();
        return activeRegistrations.uniqueTeamCount(locationId);
    }
}
