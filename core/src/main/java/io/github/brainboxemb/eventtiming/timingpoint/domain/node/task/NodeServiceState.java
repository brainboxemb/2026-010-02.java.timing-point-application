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

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Passive Task Lane-owned state behind {@link NodeService}.
 *
 * <p>This object owns mutable race/reference data and derived registration
 * history. It has no executor or lifecycle of its own. Normal state mutation
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
    private final UniqueTeamRegistrations uniqueTeamRegistrations =
            new UniqueTeamRegistrations();
    private final List<TimingData> committedTimingData =
            new ArrayList<TimingData>();

    private boolean derivedTimingReady;

    /**
     * Cross-lane health signal set when a required TimingData handoff is lost.
     */
    private volatile boolean derivedTimingStale;

    /** Creates the passive Task Lane state from immutable EventData reference data. */
    public NodeServiceState(EventData eventData) {
        checkArgument(eventData != null, "eventData must not be null");
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

    /** Rebuilds derived registration state from the committed startup history. */
    void rebuildDerivedTiming(List<TimingData> loadedData) {
        checkArgument(loadedData != null, "loadedData must not be null");

        committedTimingData.clear();
        uniqueTeamRegistrations.reset();

        for (TimingData data : loadedData) {
            committedTimingData.add(data);
            updateUniqueTeamRegistrations(data);
        }

        derivedTimingStale = false;
        derivedTimingReady = true;
    }

    /** Applies one newly committed TimingData record to derived registration state. */
    void applyCommittedTimingData(TimingData data) {
        checkArgument(data != null, "data must not be null");
        if (!derivedTimingReady) {
            derivedTimingStale = true;
            return;
        }

        committedTimingData.add(data);
        updateUniqueTeamRegistrations(data);
    }

    /** Returns a defensive copy of committed TimingData after availability checks. */
    List<TimingData> committedTimingDataSnapshot() {
        ensureDerivedTimingAvailable();
        return new ArrayList<TimingData>(committedTimingData);
    }

    /** Fails when derived timing has not been initialized or became stale. */
    void ensureDerivedTimingAvailable() {
        if (!derivedTimingReady || derivedTimingStale) {
            throw new OperationException(
                    OperationException.Reason.UNAVAILABLE,
                    "Task Lane derived timing state is not available");
        }
    }

    /** Applies Central-provided race-data changes on the Task Lane. */
    @Override
    public void applyRaceData(UpstreamData.RaceData changes) {
        raceData.applyChanges(changes);
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

    /** Returns the number of unique currently registered teams. */
    @Override
    public int uniqueTeamRegistrationCount() {
        ensureDerivedTimingAvailable();
        return uniqueTeamRegistrations.uniqueTeamCount(raceData::teamIdFor);
    }

    /** Updates the unique-team index from one committed registration record. */
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
