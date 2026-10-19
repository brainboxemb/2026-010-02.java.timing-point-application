package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTimingRecord;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies the stage-passage view derived from active registrations. */
public class StageTimingTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    /** Multiple active passages for one team remain separate StageTimingRecord rows. */
    @Test
    public void keepsMultiplePassagesForOneTeam() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON, null);

        List<StageTimingRecord> passages =
                stageTimes(
                        fixture.raceData,
                        active(
                                Arrays.<TimingData>asList(
                                        automatic(
                                                1L,
                                                "A0001",
                                                Instant.parse("2026-10-13T12:00:20Z"),
                                                TimingData.RegistrationAction.ADD),
                                        automatic(
                                                2L,
                                                "A0001",
                                                Instant.parse("2026-10-13T12:00:10Z"),
                                                TimingData.RegistrationAction.ADD))),
                        ZoneOffset.UTC);

        assertEquals(2, passages.size());
        assertEquals(1L, passages.get(0).sequenceNumber());
        assertEquals(2L, passages.get(1).sequenceNumber());
        assertEquals(new TeamId("0001"), passages.get(0).teamId());
        assertEquals(new TeamId("0001"), passages.get(1).teamId());
        assertEquals(Duration.ofSeconds(20), passages.get(0).elapsed());
        assertEquals(Duration.ofSeconds(10), passages.get(1).elapsed());
    }

    @Test
    public void timeOnlyStartUsesPreviousCivilDayAcrossMidnight() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.of(23, 59, 50), null);

        List<StageTimingRecord> passages =
                stageTimes(
                        fixture.raceData,
                        active(
                                Collections.<TimingData>singletonList(
                                        automatic(
                                                1L,
                                                "A0001",
                                                Instant.parse("2026-10-13T22:00:10Z"),
                                                TimingData.RegistrationAction.ADD))),
                        ZoneId.of("Europe/Amsterdam"));

        assertEquals(1, passages.size());
        assertEquals(Duration.ofSeconds(20), passages.get(0).elapsed());
    }

    @Test
    public void explicitStartDateIsAuthoritative() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.of(23, 59, 50), LocalDate.of(2026, 10, 13));

        List<StageTimingRecord> passages =
                stageTimes(
                        fixture.raceData,
                        active(
                                Collections.<TimingData>singletonList(
                                        automatic(
                                                1L,
                                                "A0001",
                                                Instant.parse("2026-10-13T22:00:10Z"),
                                                TimingData.RegistrationAction.ADD))),
                        ZoneId.of("Europe/Amsterdam"));

        assertEquals(Duration.ofSeconds(20), passages.get(0).elapsed());
    }

    @Test
    public void liveTeamAssignmentResolvesReserveRegistration() {
        Fixture fixture = fixture();
        fixture.assignTeam("R0001", "0042");
        fixture.setStart("0042", LocalTime.NOON, null);

        List<StageTimingRecord> passages =
                stageTimes(
                        fixture.raceData,
                        active(
                                Collections.<TimingData>singletonList(
                                        automatic(
                                                1L,
                                                "R0001",
                                                Instant.parse("2026-10-13T12:00:30Z"),
                                                TimingData.RegistrationAction.ADD))),
                        ZoneOffset.UTC);

        assertEquals(1, passages.size());
        assertEquals(new TeamId("0042"), passages.get(0).teamId());
        assertEquals(new RegistrationId("R0001"), passages.get(0).registrationId());
        assertEquals(Duration.ofSeconds(30), passages.get(0).elapsed());
    }

    @Test
    public void revokedAndUnresolvedRegistrationsDoNotBecomeStageTimingRecords() {
        Fixture fixture = fixture();
        fixture.setStart("0001", LocalTime.NOON, null);

        TimingData add =
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.ADD);
        TimingData lifecycle =
                FACTORY.createNodeOpen(context(2L, Instant.parse("2026-10-13T12:00:00Z")));
        TimingData revoke =
                automatic(
                        3L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:10Z"),
                        TimingData.RegistrationAction.REV);
        TimingData unresolved =
                automatic(
                        4L,
                        "R0001",
                        Instant.parse("2026-10-13T12:00:15Z"),
                        TimingData.RegistrationAction.ADD);

        List<StageTimingRecord> passages =
                stageTimes(
                        fixture.raceData,
                        active(Arrays.asList(add, lifecycle, revoke, unresolved)),
                        ZoneOffset.UTC);

        assertTrue(passages.isEmpty());
    }

    @Test
    public void currentStartStateRecalculatesPassageWithoutChangingTimingData() {
        Fixture fixture = fixture();
        TimingData record =
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:01:00Z"),
                        TimingData.RegistrationAction.ADD);

        fixture.setStart("0001", LocalTime.NOON, null);
        StageTimingRecord first =
                stageTimes(
                                fixture.raceData,
                                active(Collections.singletonList(record)),
                                ZoneOffset.UTC)
                        .get(0);

        fixture.setStart("0001", LocalTime.of(12, 0, 30), null);
        StageTimingRecord second =
                stageTimes(
                                fixture.raceData,
                                active(Collections.singletonList(record)),
                                ZoneOffset.UTC)
                        .get(0);

        assertEquals(Instant.parse("2026-10-13T12:00:00Z"), first.startedAt().instant());
        assertEquals(Instant.parse("2026-10-13T12:00:30Z"), second.startedAt().instant());
        assertEquals(first.finishedAt(), second.finishedAt());
        assertEquals(Duration.ofSeconds(60), first.elapsed());
        assertEquals(Duration.ofSeconds(30), second.elapsed());
        assertEquals(1L, record.sequenceNumber());
    }

    /** Verifies NodeService exposes committed passages after current race-data resolution. */
    @Test
    public void nodeQueryReturnsCurrentPassageFromCommittedLogBook() throws Exception {
        TimingData committed =
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD);
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        NODE_ID,
                        new StaticPersistence(committed),
                        FACTORY,
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream);
        TimingNode node = components.node();

        activate(node);
        try {
            stream.receive(
                    DefaultUpstreamData.raceData(
                            NODE_ID,
                            1L,
                            Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet("0001", LocalTime.NOON))));
            assertTrue(components.upstreamHandler().inputAvailable());
            stream.takeSent();

            List<StageTimingRecord> passages = stageTimes(components.nodeService(), ZoneOffset.UTC);

            assertEquals(1, passages.size());
            assertEquals(1L, passages.get(0).sequenceNumber());
            assertEquals(new TeamId("0001"), passages.get(0).teamId());
            assertEquals(Duration.ofSeconds(20), passages.get(0).elapsed());
        } finally {
            deactivate(node);
        }
    }

    /** Collects a small bounded passage result for assertions only. */
    private static List<StageTimingRecord> stageTimes(
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations,
            ZoneId raceTimeZone) {

        List<StageTimingRecord> result = new ArrayList<StageTimingRecord>();
        for (ActiveRegistrations.Registration registration : activeRegistrations.values()) {
            RegistrationId registrationId = registration.registrationId();
            TeamId teamId = raceData.teamIdFor(registrationId);
            if (registration.isValid() && teamId != null && raceData.stageStartTimeFor(teamId) != null) {
                result.add(StageTiming.resolve(registration.data(), teamId, raceData.stageStartTimeFor(teamId), raceTimeZone));
            }
        }
        return result;
    }

    /** Collects a small bounded NodeService passage result for assertions only. */
    private static List<StageTimingRecord> stageTimes(NodeService nodeService, ZoneId raceTimeZone) {

        List<StageTimingRecord> result = new ArrayList<StageTimingRecord>();
        nodeService.visitStageTimingRecords(raceTimeZone, 16, result::add);
        return result;
    }

    /** Folds committed test records into the active-registration view used by StageTiming. */
    private static ActiveRegistrations active(Iterable<TimingData> records) {

        ActiveRegistrations active = new ActiveRegistrations();
        for (TimingData record : records) {
            active.apply(record);
        }
        return active;
    }

    private static TimingData.AutomaticRegistration automatic(
            long sequence,
            String registrationId,
            Instant effectiveTime,
            TimingData.RegistrationAction action) {

        return FACTORY.createAutomaticRegistration(
                context(sequence, effectiveTime),
                new RegistrationId(registrationId),
                action);
    }

    private static TimingDataFactory.Context context(long sequence, Instant effectiveTime) {

        TimingTimestamp timestamp = new TimingTimestamp(effectiveTime);
        return new TimingDataFactory.Context(NODE_ID, sequence, LOCATION_ID, timestamp, timestamp);
    }

    private static Fixture fixture() {

        return new Fixture();
    }

    private static final class Fixture {
        private final RaceDataState raceData = new RaceDataState(new DefaultEventData());

        private void assignTeam(String registrationId, String teamId) {

            raceData.teamAssignments().assign(new RegistrationId(registrationId), new TeamId(teamId));
            raceData.teamAssignments().validate();
        }

        private void setStart(String teamId, LocalTime startTime, LocalDate startDate) {

            raceData.stageStartTimes().set(
                    new TeamId(teamId),
                    startDate == null
                            ? TimingTimestamp.localTime(startTime)
                            : TimingTimestamp.localDateTime(startDate, startTime));
        }
    }

    private static final class StaticPersistence implements TimingDataPersistence {
        private final TimingData record;

        private StaticPersistence(TimingData record) {

            this.record = record;
        }

        @Override
        public LoadResult load() {

            return new LoadResult(Collections.singletonList(record), false);
        }

        @Override
        public void append(TimingData data) {

            throw new AssertionError("StageTiming test must not append TimingData");
        }
    }
}
