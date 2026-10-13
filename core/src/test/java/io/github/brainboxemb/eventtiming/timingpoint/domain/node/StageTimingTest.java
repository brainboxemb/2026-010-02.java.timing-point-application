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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.StageStartTimes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TeamAssignments;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StageTimingTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY =
            new DefaultTimingDataFactory();

    @Test
    public void timeOnlyStartUsesPreviousCivilDayAcrossMidnight() {
        Fixture fixture = fixture();
        fixture.setStart(
                "0001",
                LocalTime.of(23, 59, 50),
                null);

        StageTiming.Result result =
                fixture.stageTiming.calculate(
                        automatic(
                                1L,
                                "RT-A-0001",
                                Instant.parse("2026-10-13T22:00:10Z"),
                                TimingData.RegistrationAction.ADD),
                        ZoneId.of("Europe/Amsterdam"));

        assertSame(StageTiming.Outcome.RESOLVED, result.outcome());
        assertTrue(result.resolved());
        assertEquals(new TeamId("0001"), result.teamId());
        assertEquals(
                Instant.parse("2026-10-13T21:59:50Z"),
                result.startInstant());
        assertEquals(Duration.ofSeconds(20), result.elapsed());
    }

    @Test
    public void explicitStartDateIsAuthoritative() {
        Fixture fixture = fixture();
        fixture.setStart(
                "0001",
                LocalTime.of(23, 59, 50),
                LocalDate.of(2026, 10, 13));

        StageTiming.Result result =
                fixture.stageTiming.calculate(
                        automatic(
                                1L,
                                "RT-A-0001",
                                Instant.parse("2026-10-13T22:00:10Z"),
                                TimingData.RegistrationAction.ADD),
                        ZoneId.of("Europe/Amsterdam"));

        assertSame(StageTiming.Outcome.RESOLVED, result.outcome());
        assertEquals(
                Instant.parse("2026-10-13T21:59:50Z"),
                result.startInstant());
        assertEquals(Duration.ofSeconds(20), result.elapsed());
    }

    @Test
    public void activeTeamAssignmentIsFallbackWhenEventDataHasNoTeam() {
        Fixture fixture = fixture();
        fixture.assignTeam(
                "RT-R-0001",
                "0042");
        fixture.setStart(
                "0042",
                LocalTime.of(12, 0),
                null);

        StageTiming.Result result =
                fixture.stageTiming.calculate(
                        automatic(
                                1L,
                                "RT-R-0001",
                                Instant.parse("2026-10-13T12:00:30Z"),
                                TimingData.RegistrationAction.ADD),
                        ZoneOffset.UTC);

        assertSame(StageTiming.Outcome.RESOLVED, result.outcome());
        assertEquals(new TeamId("0042"), result.teamId());
        assertEquals(Duration.ofSeconds(30), result.elapsed());
    }

    @Test
    public void manualRegistrationUsesSameStageTimingSemantics() {
        Fixture fixture = fixture();
        fixture.setStart(
                "0001",
                LocalTime.of(12, 0),
                null);

        TimingData record =
                FACTORY.createManualRegistration(
                        context(
                                1L,
                                Instant.parse("2026-10-13T12:00:15Z")),
                        new RegistrationId("RT-A-0001"),
                        TimingData.ManualTimeSource.OPERATOR_ENTERED);

        StageTiming.Result result =
                fixture.stageTiming.calculate(
                        record,
                        ZoneOffset.UTC);

        assertSame(StageTiming.Outcome.RESOLVED, result.outcome());
        assertEquals(Duration.ofSeconds(15), result.elapsed());
    }

    @Test
    public void missingTeamAndStartAreExplicitlyUnresolved() {
        Fixture fixture = fixture();

        StageTiming.Result missingTeam =
                fixture.stageTiming.calculate(
                        automatic(
                                1L,
                                "RT-R-0001",
                                Instant.parse("2026-10-13T12:00:00Z"),
                                TimingData.RegistrationAction.ADD),
                        ZoneOffset.UTC);

        assertSame(
                StageTiming.Outcome.TEAM_UNRESOLVED,
                missingTeam.outcome());
        assertFalse(missingTeam.resolved());
        assertEquals(
                new RegistrationId("RT-R-0001"),
                missingTeam.registrationId());
        assertNull(missingTeam.teamId());

        StageTiming.Result missingStart =
                fixture.stageTiming.calculate(
                        automatic(
                                2L,
                                "RT-A-0001",
                                Instant.parse("2026-10-13T12:00:00Z"),
                                TimingData.RegistrationAction.ADD),
                        ZoneOffset.UTC);

        assertSame(
                StageTiming.Outcome.START_UNRESOLVED,
                missingStart.outcome());
        assertEquals(new TeamId("0001"), missingStart.teamId());
        assertNull(missingStart.elapsed());
    }

    @Test
    public void revokeAndLifecycleRecordsAreNotActiveStagePassages() {
        Fixture fixture = fixture();
        fixture.setStart(
                "0001",
                LocalTime.of(12, 0),
                null);

        StageTiming.Result revoked =
                fixture.stageTiming.calculate(
                        automatic(
                                1L,
                                "RT-A-0001",
                                Instant.parse("2026-10-13T12:00:10Z"),
                                TimingData.RegistrationAction.REV),
                        ZoneOffset.UTC);

        assertSame(StageTiming.Outcome.REVOKED, revoked.outcome());
        assertNull(revoked.elapsed());

        TimingData open =
                FACTORY.createNodeOpen(
                        context(
                                2L,
                                Instant.parse("2026-10-13T12:00:00Z")));

        StageTiming.Result lifecycle =
                fixture.stageTiming.calculate(
                        open,
                        ZoneOffset.UTC);

        assertSame(
                StageTiming.Outcome.NOT_REGISTRATION,
                lifecycle.outcome());
    }

    @Test
    public void currentStartStateRecalculatesWithoutChangingTimingData() {
        Fixture fixture = fixture();
        TimingData record =
                automatic(
                        1L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:01:00Z"),
                        TimingData.RegistrationAction.ADD);

        fixture.setStart(
                "0001",
                LocalTime.of(12, 0),
                null);
        StageTiming.Result first =
                fixture.stageTiming.calculate(
                        record,
                        ZoneOffset.UTC);

        fixture.setStart(
                "0001",
                LocalTime.of(12, 0, 30),
                null);
        StageTiming.Result second =
                fixture.stageTiming.calculate(
                        record,
                        ZoneOffset.UTC);

        assertEquals(Duration.ofSeconds(60), first.elapsed());
        assertEquals(Duration.ofSeconds(30), second.elapsed());
        assertEquals(1L, record.sequenceNumber());
        assertEquals(
                Instant.parse("2026-10-13T12:01:00Z"),
                record.effectiveTime().instant());
    }

    @Test
    public void nodeQueryUsesCommittedLogBookAndCurrentUpstreamData()
            throws Exception {
        TimingData committed =
                automatic(
                        1L,
                        "RT-A-0001",
                        Instant.parse("2026-10-13T12:00:20Z"),
                        TimingData.RegistrationAction.ADD);
        TimingNode node =
                new TimingNode(
                        NODE_ID,
                        new StaticPersistence(committed),
                        FACTORY,
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData());

        node.activate();
        try {
            node.upstreamMessagePort()
                    .applyRaceData(
                            DefaultUpstreamData.raceData(
                                    NODE_ID,
                                    1L,
                                    Collections.<UpstreamData.ReserveTagMapChange>emptyList(),
                                    Collections.singletonList(
                                            DefaultUpstreamData.stageStartSet(
                                                    "0001",
                                                    LocalTime.of(12, 0)))));

            StageTiming.Result resolved =
                    node.query(
                            TimingNodeQueries.stageTiming(
                                    1L,
                                    ZoneOffset.UTC));
            StageTiming.Result missing =
                    node.query(
                            TimingNodeQueries.stageTiming(
                                    2L,
                                    ZoneOffset.UTC));

            assertSame(StageTiming.Outcome.RESOLVED, resolved.outcome());
            assertEquals(Duration.ofSeconds(20), resolved.elapsed());
            assertSame(
                    StageTiming.Outcome.TIMING_DATA_NOT_FOUND,
                    missing.outcome());
            assertEquals(2L, missing.sequenceNumber());
        } finally {
            node.deactivate();
        }
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

    private static TimingDataFactory.Context context(
            long sequence,
            Instant effectiveTime) {
        TimingTimestamp timestamp =
                new TimingTimestamp(effectiveTime);
        return new TimingDataFactory.Context(
                NODE_ID,
                sequence,
                LOCATION_ID,
                timestamp,
                timestamp);
    }

    private static Fixture fixture() {
        return new Fixture();
    }

    private static final class Fixture {
        private final TeamAssignments teamAssignments =
                new TeamAssignments();
        private final StageStartTimes stageStartTimes =
                new StageStartTimes();
        private final StageTiming stageTiming =
                new StageTiming(
                        new DefaultEventData(),
                        teamAssignments,
                        stageStartTimes);

        private void assignTeam(
                String registrationId,
                String teamId) {
            teamAssignments.assign(
                    new RegistrationId(registrationId),
                    new TeamId(teamId));
            teamAssignments.validate();
        }

        private void setStart(
                String teamId,
                LocalTime startTime,
                LocalDate startDate) {
            stageStartTimes.set(
                    new TeamId(teamId),
                    startTime,
                    startDate);
        }
    }

    private static final class StaticPersistence
            implements TimingDataPersistence {
        private final TimingData record;

        private StaticPersistence(TimingData record) {
            this.record = record;
        }

        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.singletonList(record),
                    false);
        }

        @Override
        public void append(TimingData data) {
            throw new AssertionError(
                    "StageTiming test must not append TimingData");
        }
    }
}
