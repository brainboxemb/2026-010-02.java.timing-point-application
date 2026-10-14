package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.UniqueTeamRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamTaskState;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.ArrayList;
import java.util.List;

/**
 * Passive Task Lane-owned state behind {@link NodeService}.
 *
 * <p>This object owns mutable race/reference data and derived registration
 * history. It has no executor or lifecycle of its own. Every mutation/read that
 * depends on consistency is performed by callers while the NodeService Task
 * Lane is current.</p>
 *
 * <p>The class implements the narrow upstream state port so {@code NodeService}
 * remains the composition/service owner instead of becoming an implementation
 * detail of its {@code UpstreamHandler} child.</p>
 */
final class NodeServiceState implements UpstreamTaskState {
    private final RaceDataState raceData;
    private final UniqueTeamRegistrations uniqueTeamRegistrations =
            new UniqueTeamRegistrations();
    private final List<TimingData> committedTimingData =
            new ArrayList<TimingData>();

    private boolean derivedTimingReady;
    private boolean derivedTimingStale;

    NodeServiceState(EventData eventData) {
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        this.raceData = new RaceDataState(eventData);
    }

    TeamAssignments teamAssignments() {
        return raceData.teamAssignments();
    }

    StageStartTimes stageStartTimes() {
        return raceData.stageStartTimes();
    }

    void markDerivedTimingUnavailable() {
        derivedTimingReady = false;
    }

    void markDerivedTimingStale() {
        derivedTimingStale = true;
    }

    void rebuildDerivedTiming(List<TimingData> loadedData) {
        if (loadedData == null) {
            throw new IllegalArgumentException(
                    "loadedData must not be null");
        }

        committedTimingData.clear();
        uniqueTeamRegistrations.reset();

        for (TimingData data : loadedData) {
            committedTimingData.add(data);
            updateUniqueTeamRegistrations(data);
        }

        derivedTimingStale = false;
        derivedTimingReady = true;
    }

    void applyCommittedTimingData(TimingData data) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null");
        }
        if (!derivedTimingReady) {
            derivedTimingStale = true;
            return;
        }

        committedTimingData.add(data);
        updateUniqueTeamRegistrations(data);
    }

    List<TimingData> committedTimingDataSnapshot() {
        ensureDerivedTimingAvailable();
        return new ArrayList<TimingData>(committedTimingData);
    }

    void ensureDerivedTimingAvailable() {
        if (!derivedTimingReady || derivedTimingStale) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "Task Lane derived timing state is not available");
        }
    }

    @Override
    public void applyRaceData(UpstreamData.RaceData changes) {
        raceData.applyChanges(changes);
    }

    @Override
    public TeamId teamIdFor(RegistrationId registrationId) {
        return raceData.teamIdFor(registrationId);
    }

    @Override
    public TimingTimestamp stageStartTimeFor(TeamId teamId) {
        return raceData.stageStartTimeFor(teamId);
    }

    @Override
    public int uniqueTeamRegistrationCount() {
        ensureDerivedTimingAvailable();
        return uniqueTeamRegistrations.uniqueTeamCount(raceData::teamIdFor);
    }

    private void updateUniqueTeamRegistrations(TimingData data) {
        final RegistrationId registrationId;
        final TimingData.RegistrationAction action;

        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration registration =
                    (TimingData.AutomaticRegistration) data;
            registrationId = registration.registrationId();
            action = registration.action();
        } else if (data instanceof TimingData.ManualRegistration) {
            TimingData.ManualRegistration registration =
                    (TimingData.ManualRegistration) data;
            registrationId = registration.registrationId();
            action = registration.action();
        } else {
            return;
        }

        if (action == TimingData.RegistrationAction.ADD) {
            uniqueTeamRegistrations.registered(registrationId);
        } else if (action == TimingData.RegistrationAction.REV) {
            uniqueTeamRegistrations.revoked(registrationId);
        }
    }
}
