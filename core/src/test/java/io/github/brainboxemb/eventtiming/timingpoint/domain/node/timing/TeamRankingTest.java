package io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.TeamAssignments;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies incrementally maintained selection of one ranking passage per team. */
public class TeamRankingTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    /** The earliest active finish is selected without removing duplicate passages. */
    @Test
    public void selectsFastestActivePassageForOneTeam() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        2L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));

        List<TeamRank> ranking = fixture.ranking();

        assertEquals(1, ranking.size());
        assertEquals(1, ranking.get(0).rank());
        assertEquals(2L, ranking.get(0).stageTime().sequenceNumber());
        assertEquals(Duration.ofSeconds(10), ranking.get(0).elapsed());
    }

    /** Revoking the selected passage promotes the next active candidate directly. */
    @Test
    public void revokeSelectedPassagePromotesNextCandidate() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        2L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        3L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.REV));

        List<TeamRank> ranking = fixture.ranking();

        assertEquals(1, ranking.size());
        assertEquals(1L, ranking.get(0).stageTime().sequenceNumber());
        assertEquals(Duration.ofSeconds(20), ranking.get(0).elapsed());
    }

    /** Equal selected elapsed times share competition rank across teams. */
    @Test
    public void equalTeamTimesShareRank() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);
        fixture.setStart("0002", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        2L,
                        "RT-A-0002",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));

        List<TeamRank> ranking = fixture.ranking();

        assertEquals(2, ranking.size());
        assertEquals(1, ranking.get(0).rank());
        assertEquals(1, ranking.get(1).rank());
    }

    /** A reserve registration enters ranking when its live team assignment appears. */
    @Test
    public void teamAssignmentMovesExistingCandidateIntoRanking() {
        Fixture fixture = fixture();
        fixture.setStart("0042", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "RT-R-0001",
                        Instant.parse("2026-10-13T12:00:30Z"),
                        TimingData.RegistrationAction.ADD));

        assertTrue(fixture.ranking().isEmpty());

        RegistrationId registrationId = new RegistrationId("RT-R-0001");
        TeamId teamId = new TeamId("0042");
        fixture.activeRegistrations.teamAssignmentChanged(registrationId, teamId);
        fixture.teamRanking.teamAssignmentChanged(registrationId, teamId);

        List<TeamRank> ranking = fixture.ranking();

        assertEquals(1, ranking.size());
        assertEquals(teamId, ranking.get(0).stageTime().teamId());
        assertEquals(1L, ranking.get(0).stageTime().sequenceNumber());
    }

    private static Fixture fixture() {

        return new Fixture();
    }

    private static TimingData.AutomaticRegistration automatic(
            long sequence,
            String registrationId,
            Instant effectiveTime,
            TimingData.RegistrationAction action) {

        TimingTimestamp timestamp = new TimingTimestamp(effectiveTime);
        TimingDataFactory.Context context =
                new TimingDataFactory.Context(NODE_ID, sequence, LOCATION_ID, timestamp, timestamp);
        return FACTORY.createAutomaticRegistration(
                context,
                new RegistrationId(registrationId),
                action);
    }

    private static final class Fixture {
        private final DefaultEventData eventData = new DefaultEventData();
        private final TeamAssignments teamAssignments = new TeamAssignments();
        private final StageStartTimes stageStartTimes = new StageStartTimes();
        private final ActiveRegistrations activeRegistrations = new ActiveRegistrations();
        private final StageTiming stageTiming =
                new StageTiming(eventData, teamAssignments, stageStartTimes);
        private final TeamRanking teamRanking = new TeamRanking(stageTiming);

        private void setStart(String teamId, LocalTime startTime) {

            stageStartTimes.set(new TeamId(teamId), TimingTimestamp.localTime(startTime));
        }

        private void add(TimingData data) {

            ActiveRegistrations.Change change =
                    activeRegistrations.apply(data, eventData::teamIdFor);
            teamRanking.apply(change);
        }

        private List<TeamRank> ranking() {

            List<TeamRank> result = new ArrayList<TeamRank>();
            teamRanking.visit(LOCATION_ID, ZoneOffset.UTC, 16, result::add);
            return result;
        }
    }
}
