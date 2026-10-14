package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import java.util.Collections;
import java.util.List;

/**
 * Data-only fake for upstream protocol tests. No worker, transport or mutable
 * race state is exposed to a protocol handler.
 */
final class TestUpstreamNodeData implements UpstreamNodeData {
    private final RaceDataState raceData = new RaceDataState(EventData.empty());

    @Override
    public void applyRaceData(UpstreamData.RaceData message) {
        raceData.applyChanges(message);
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
        return 0;
    }

    @Override
    public long timingDataHead() {
        return 0L;
    }

    @Override
    public List<TimingData> timingDataRange(long fromSequence, int limit) {
        return Collections.emptyList();
    }
}
