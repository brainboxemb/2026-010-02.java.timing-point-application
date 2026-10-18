package io.github.brainboxemb.eventtiming.timingpoint.domain.node.service;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
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
 * <p>This object owns mutable race data and derived registration state. It has no executor
 * or lifecycle of its own. Normal mutation and consistent reads happen only on the node
 * Task Lane. The volatile stale marker may also be set when a cross-lane handoff fails.</p>
 *
 * <p>Runtime composition shares one instance with the upstream boundary. Upstream code sees
 * only the narrow {@link UpstreamTaskState} contract.</p>
 */
public final class NodeServiceState implements UpstreamTaskState {
    private final EventData eventData;
    private final RaceDataState raceData;
    private final ActiveRegistrations activeRegistrations = new ActiveRegistrations();

    private boolean derivedTimingReady;
    private volatile boolean derivedTimingStale;

    /** Creates passive Task Lane state from the configured EventData. */
    public NodeServiceState(EventData eventData) {

        failIfInvalidArgument(eventData != null, "eventData must not be null");

        this.eventData = eventData;
        raceData = new RaceDataState(eventData);
    }

    /** Returns EventData used by Task Lane derived timing. */
    EventData eventData() {

        return eventData;
    }

    /** Returns team assignments used by StageTiming. */
    TeamAssignments teamAssignments() {

        return raceData.teamAssignments();
    }

    /** Returns stage starts used by StageTiming. */
    StageStartTimes stageStartTimes() {

        return raceData.stageStartTimes();
    }

    /** Marks derived timing unavailable when NodeService stops. */
    void markDerivedTimingUnavailable() {

        derivedTimingReady = false;
    }

    /** Marks derived timing stale after a required Task Lane handoff failed. */
    void markDerivedTimingStale() {

        derivedTimingStale = true;
    }

    /** Clears derived registration state before reading recovered TimingData. */
    void beginDerivedTimingRecovery() {

        activeRegistrations.reset();
        derivedTimingReady = false;
        derivedTimingStale = false;
    }

    /** Applies one ordered TimingData record. */
    void applyTimingData(TimingData data) {

        failIfInvalidArgument(data != null, "data must not be null");
        activeRegistrations.apply(data, raceData::teamIdFor);
    }

    /** Marks derived timing ready after reaching the recovered LogBook head. */
    void completeDerivedTimingRecovery() {

        derivedTimingStale = false;
        derivedTimingReady = true;
    }

    /** Returns active registrations after checking derived-state availability. */
    ActiveRegistrations activeRegistrations() {

        ensureDerivedTimingAvailable();
        return activeRegistrations;
    }

    /** Applies Central-provided race-data changes on the Task Lane. */
    @Override
    public void applyRaceData(UpstreamData.RaceData changes) {

        raceData.applyChanges(changes);
        for (UpstreamData.TeamAssignmentChange change : changes.teamAssignmentChanges()) {
            RegistrationId registrationId = change.registrationId();
            activeRegistrations.teamAssignmentChanged(registrationId, raceData.teamIdFor(registrationId));
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
    int uniqueTeamRegistrationCount(LocationId locationId) {

        ensureDerivedTimingAvailable();
        return activeRegistrations.uniqueTeamCount(locationId);
    }

    /** Fails a result-bearing query while derived timing is unavailable. */
    private void ensureDerivedTimingAvailable() {

        if (!derivedTimingReady || derivedTimingStale) {
            throw new IllegalStateException("Task Lane derived timing state is not available");
        }
    }
}
