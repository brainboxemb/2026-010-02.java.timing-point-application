package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.List;

/**
 * Typed Data Lane operations required by the node-scoped upstream protocol.
 *
 * <p>No mutable LogBook, TeamAssignments or StageStartTimes references cross
 * this boundary. Only timingDataHead() is a safely published CURRENT read;
 * all other operations must execute during a Data Lane turn. This is the
 * data-side contract to be used for asynchronous Task Lane handoffs.</p>
 */
public interface UpstreamNodeData {
    long timingDataHead();
    List<TimingData> timingDataRange(long fromSequence, int limit);
    void applyRaceData(UpstreamData.RaceData changes);
    TeamId teamIdFor(RegistrationId registrationId);
    TimingTimestamp stageStartTimeFor(TeamId teamId);
    int uniqueTeamRegistrationCount();
}
