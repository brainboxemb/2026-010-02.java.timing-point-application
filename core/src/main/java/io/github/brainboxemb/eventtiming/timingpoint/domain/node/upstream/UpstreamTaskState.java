package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * Task Lane-owned live state required by the upstream protocol.
 *
 * <p>The port is implemented by passive Task Lane state, not by the top-level
 * NodeService. Implementations are invoked only from the TimingNode Task Lane.
 * The interface deliberately exposes semantic operations rather than mutable
 * TeamAssignments, StageStartTimes or UniqueTeamRegistrations objects.</p>
 */
public interface UpstreamTaskState {
    void applyRaceData(UpstreamData.RaceData changes);

    TeamId teamIdFor(RegistrationId registrationId);

    TimingTimestamp stageStartTimeFor(TeamId teamId);

    int uniqueTeamRegistrationCount();
}
