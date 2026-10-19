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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies every valid registration receives a provisional ranking position. */
public class RegistrationRankingTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    /** Every valid passage is ranked, including multiple passages of the same team. */
    @Test
    public void ranksEveryValidPassageForOneTeam() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        2L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));

        List<RegistrationRank> ranking = fixture.ranking();

        assertEquals(2, ranking.size());
        assertEquals(1, ranking.get(0).rank());
        assertEquals(2L, ranking.get(0).timingRecord().sequenceNumber());
        assertEquals(Duration.ofSeconds(10), ranking.get(0).elapsed());
        assertEquals(2, ranking.get(1).rank());
        assertEquals(1L, ranking.get(1).timingRecord().sequenceNumber());
    }

    /** Revoking the fastest passage removes it from the ranking but not the display history. */
    @Test
    public void revokeRemovesInvalidPassageFromRanking() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        2L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        3L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.REV));

        List<RegistrationRank> ranking = fixture.ranking();

        assertEquals(1, ranking.size());
        assertEquals(1L, ranking.get(0).timingRecord().sequenceNumber());
        assertEquals(Duration.ofSeconds(20), ranking.get(0).elapsed());
    }

    /** An unmatched REV leaves the current ranking unchanged. */
    @Test
    public void unmatchedRevokeDoesNotChangeRanking() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        2L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:30Z"),
                        TimingData.RegistrationAction.REV));

        List<RegistrationRank> ranking = fixture.ranking();

        assertEquals(1, ranking.size());
        assertEquals(1L, ranking.get(0).timingRecord().sequenceNumber());
    }

    /** Equal elapsed times share a competition rank across registrations. */
    @Test
    public void equalElapsedTimesShareRank() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);
        fixture.setStart("0002", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automatic(
                        2L,
                        "A0002",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));

        List<RegistrationRank> ranking = fixture.ranking();

        assertEquals(2, ranking.size());
        assertEquals(1, ranking.get(0).rank());
        assertEquals(1, ranking.get(1).rank());
    }

    /** Ranking registrations stay scoped to their TimingData location. */
    @Test
    public void rankingIsScopedPerLocation() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON);

        fixture.add(
                automaticAt(
                        1L,
                        1,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD));
        fixture.add(
                automaticAt(
                        2L,
                        2,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD));

        assertEquals(1L, fixture.ranking(new LocationId(1)).get(0).timingRecord().sequenceNumber());
        assertEquals(2L, fixture.ranking(new LocationId(2)).get(0).timingRecord().sequenceNumber());
    }

    /** A reserve registration enters ranking when its live team assignment appears. */
    @Test
    public void assignmentChangeChangesRankingWithoutReapplyingRegistrations() {
        Fixture fixture = fixture();
        fixture.setStart("0042", LocalTime.NOON);

        fixture.add(
                automatic(
                        1L,
                        "R0001",
                        Instant.parse("2026-10-13T12:00:30Z"),
                        TimingData.RegistrationAction.ADD));

        assertTrue(fixture.ranking().isEmpty());

        RegistrationId registrationId = new RegistrationId("R0001");
        TeamId teamId = new TeamId("0042");
        fixture.raceData.teamAssignments().assign(registrationId, teamId);

        List<RegistrationRank> ranking = fixture.ranking();

        assertEquals(1, ranking.size());
        assertEquals(teamId, ranking.get(0).timingRecord().teamId());
        assertEquals(1L, ranking.get(0).timingRecord().sequenceNumber());

        fixture.raceData.teamAssignments().remove(registrationId);
        assertTrue(fixture.ranking().isEmpty());
    }

    /** 400 passages by 350 teams must produce 400 ranked rows but only 350 unique registered teams. */
    @Test
    public void ranksFourHundredRegistrationsFromThreeHundredFiftyTeams() {
        Fixture fixture = fixture();
        RegisteredTeams registeredTeams = new RegisteredTeams();

        for (int team = 1; team <= 350; team++) {
            fixture.setStart(String.format(Locale.ROOT, "%04d", team), LocalTime.NOON);
        }

        for (int sequence = 1; sequence <= 400; sequence++) {
            int team = (sequence - 1) % 350 + 1;
            String registrationId = String.format(Locale.ROOT, "A%04d", team);
            TimingData registration = automatic(
                    sequence, registrationId, Instant.parse("2026-10-13T12:00:00Z").plusMillis(sequence),
                    TimingData.RegistrationAction.ADD);
            ActiveRegistrations.Change change = fixture.activeRegistrations.apply(registration);
            registeredTeams.apply(change, fixture.raceData.teamIdFor(change.registrationId()));
        }

        List<RegistrationRank> ranking = new ArrayList<>();
        fixture.registrationRanking.visit(fixture.activeRegistrations, LOCATION_ID, ZoneOffset.UTC, 400, ranking::add);

        assertEquals(400, ranking.size());
        assertEquals(350, registeredTeams.uniqueTeamCount(LOCATION_ID));
        assertEquals(1, ranking.get(0).rank());
        assertEquals(400, ranking.get(399).rank());
    }

    private static Fixture fixture() {

        return new Fixture();
    }

    private static TimingData.AutomaticRegistration automatic(
            long sequence,
            String registrationId,
            Instant effectiveTime,
            TimingData.RegistrationAction action) {

        return automaticAt(sequence, 1, registrationId, effectiveTime, action);
    }

    private static TimingData.AutomaticRegistration automaticAt(
            long sequence,
            int location,
            String registrationId,
            Instant effectiveTime,
            TimingData.RegistrationAction action) {

        TimingTimestamp timestamp = new TimingTimestamp(effectiveTime);
        TimingDataFactory.Context context =
                new TimingDataFactory.Context(
                        NODE_ID,
                        sequence,
                        new LocationId(location),
                        timestamp,
                        timestamp);
        return FACTORY.createAutomaticRegistration(
                context,
                new RegistrationId(registrationId),
                action);
    }

    private static final class Fixture {
        private final DefaultEventData eventData = new DefaultEventData();
        private final RaceDataState raceData = new RaceDataState(eventData);
        private final ActiveRegistrations activeRegistrations = new ActiveRegistrations();
        private final RegistrationRanking registrationRanking = new RegistrationRanking(raceData);

        private void setStart(String teamId, LocalTime startTime) {

            raceData.stageStartTimes().set(new TeamId(teamId), TimingTimestamp.localTime(startTime));
        }

        private void add(TimingData data) {

            activeRegistrations.apply(data);
        }

        private List<RegistrationRank> ranking() {

            return ranking(LOCATION_ID);
        }

        private List<RegistrationRank> ranking(LocationId locationId) {

            List<RegistrationRank> result = new ArrayList<RegistrationRank>();
            registrationRanking.visit(activeRegistrations, locationId, ZoneOffset.UTC, 16, result::add);
            return result;
        }
    }
}
