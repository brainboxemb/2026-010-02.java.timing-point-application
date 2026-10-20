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
import java.util.Locale;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Verifies one-passage provisional rank calculation without a materialized ranking list. */
public class RegistrationRankingTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();
    private static final Instant START = Instant.parse("2026-10-13T12:00:00Z");

    @Test
    public void ranksEachRepeatedPassageIndependently() {
        Fixture fixture = fixture();
        fixture.setStart("0001", START);
        fixture.add(automatic(1L, "A0001", START.plusSeconds(20), TimingData.RegistrationAction.ADD));
        fixture.add(automatic(2L, "A0001", START.plusSeconds(10), TimingData.RegistrationAction.ADD));

        RegistrationRank first = fixture.rank(1L);
        RegistrationRank second = fixture.rank(2L);

        assertEquals(2, first.rank());
        assertEquals(Duration.ofSeconds(20), first.elapsed());
        assertEquals(1, second.rank());
        assertEquals(Duration.ofSeconds(10), second.elapsed());
    }

    @Test
    public void revokedPassageIsNotRankableAndRemainingPassageMovesToFirst() {
        Fixture fixture = fixture();
        fixture.setStart("0001", START);
        fixture.add(automatic(1L, "A0001", START.plusSeconds(20), TimingData.RegistrationAction.ADD));
        fixture.add(automatic(2L, "A0001", START.plusSeconds(10), TimingData.RegistrationAction.ADD));
        ActiveRegistrations.Registration fastest = fixture.registration(2L);
        fixture.add(automatic(3L, "A0001", START.plusSeconds(10), TimingData.RegistrationAction.REV));

        assertNull(fixture.rank(fastest));
        assertEquals(1, fixture.rank(1L).rank());
    }

    @Test
    public void equalElapsedTimesShareCompetitionRank() {
        Fixture fixture = fixture();
        fixture.setStart("0001", START);
        fixture.setStart("0002", START);
        fixture.add(automatic(1L, "A0001", START.plusSeconds(10), TimingData.RegistrationAction.ADD));
        fixture.add(automatic(2L, "A0002", START.plusSeconds(10), TimingData.RegistrationAction.ADD));

        assertEquals(1, fixture.rank(1L).rank());
        assertEquals(1, fixture.rank(2L).rank());
    }

    @Test
    public void rankingOnlyCountsRegistrationsAtTheSameLocation() {
        Fixture fixture = fixture();
        fixture.setStart("0001", START);
        fixture.add(automaticAt(1L, 1, "A0001", START.plusSeconds(20), TimingData.RegistrationAction.ADD));
        fixture.add(automaticAt(2L, 2, "A0001", START.plusSeconds(10), TimingData.RegistrationAction.ADD));

        assertEquals(1, fixture.rank(1L).rank());
        assertEquals(1, fixture.rank(2L).rank());
    }

    @Test
    public void currentAssignmentDeterminesWhetherReservePassageCanBeRanked() {
        Fixture fixture = fixture();
        fixture.setStart("0042", START);
        fixture.add(automatic(1L, "R0001", START.plusSeconds(30), TimingData.RegistrationAction.ADD));
        ActiveRegistrations.Registration registration = fixture.registration(1L);

        assertNull(fixture.rank(registration));

        RegistrationId registrationId = new RegistrationId("R0001");
        TeamId teamId = new TeamId("0042");
        fixture.raceData.teamAssignments().assign(registrationId, teamId);
        assertEquals(1, fixture.rank(registration).rank());

        fixture.raceData.teamAssignments().remove(registrationId);
        assertNull(fixture.rank(registration));
    }

    /** One rank query over 400 passages remains one scan and keeps repeated teams separate. */
    @Test
    public void ranksOneOfFourHundredPassagesWithoutBuildingFullRanking() {
        Fixture fixture = fixture();
        RegisteredTeams registeredTeams = new RegisteredTeams();

        for (int team = 1; team <= 350; team++) {
            fixture.setStart(String.format(Locale.ROOT, "%04d", team), START);
        }

        for (int sequence = 1; sequence <= 400; sequence++) {
            int team = (sequence - 1) % 350 + 1;
            String registrationId = String.format(Locale.ROOT, "A%04d", team);
            TimingData registration =
                    automatic(
                            sequence,
                            registrationId,
                            START.plusMillis(sequence),
                            TimingData.RegistrationAction.ADD);
            ActiveRegistrations.Change change = fixture.activeRegistrations.apply(registration);
            registeredTeams.apply(change, fixture.raceData.teamIdFor(change.registrationId()));
        }

        assertEquals(350, registeredTeams.uniqueTeamCount(LOCATION_ID));
        assertEquals(1, fixture.rank(1L).rank());
        assertEquals(400, fixture.rank(400L).rank());
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

        private void setStart(String teamId, Instant startTime) {

            raceData.stageStartTimes().set(new TeamId(teamId), startTime.toEpochMilli());
        }

        private void add(TimingData data) {

            activeRegistrations.apply(data);
        }

        private ActiveRegistrations.Registration registration(long sequence) {

            for (ActiveRegistrations.Registration registration : activeRegistrations.values()) {
                if (registration.sequenceNumber() == sequence) {
                    return registration;
                }
            }
            throw new AssertionError("missing registration sequence " + sequence);
        }

        private RegistrationRank rank(long sequence) {

            return rank(registration(sequence));
        }

        private RegistrationRank rank(ActiveRegistrations.Registration registration) {

            return RegistrationRanking.rank(activeRegistrations, raceData, registration);
        }
    }
}
