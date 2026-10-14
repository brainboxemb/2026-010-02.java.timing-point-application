package io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/** Task-state fake for RACE_DATA protocol tests. */
final class TestUpstreamTaskState implements UpstreamTaskState {
    private final RaceDataState raceData = new RaceDataState(EventData.empty());

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
        return 0;
    }
}
