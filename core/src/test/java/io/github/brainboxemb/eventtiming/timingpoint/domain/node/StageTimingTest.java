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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTimingRecord;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies stage timing uses only already-normalized absolute internal time. */
public class StageTimingTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final LocationId LOCATION_ID = new LocationId(1);
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    /** Multiple active passages for one team remain separate StageTimingRecord rows. */
    @Test
    public void keepsMultiplePassagesForOneTeam() {
        Fixture fixture = fixture();
        fixture.setStart("0001", Instant.parse("2026-10-13T12:00:00Z"));

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
                                                TimingData.RegistrationAction.ADD))));

        assertEquals(2, passages.size());
        assertEquals(1L, passages.get(0).sequenceNumber());
        assertEquals(2L, passages.get(1).sequenceNumber());
        assertEquals(new TeamId("0001"), passages.get(0).teamId());
        assertEquals(Duration.ofSeconds(20), passages.get(0).elapsed());
        assertEquals(Duration.ofSeconds(10), passages.get(1).elapsed());
    }

    /** Crossing UTC midnight requires no civil-time or ZoneId logic in StageTiming. */
    @Test
    public void absoluteStartCrossesMidnightBySimpleSubtraction() {
        Fixture fixture = fixture();
        fixture.setStart("0001", Instant.parse("2026-10-13T23:59:50Z"));

        StageTimingRecord passage =
                stageTimes(
                                fixture.raceData,
                                active(
                                        Collections.<TimingData>singletonList(
                                                automatic(
                                                        1L,
                                                        "A0001",
                                                        Instant.parse("2026-10-14T00:00:10Z"),
                                                        TimingData.RegistrationAction.ADD))))
                        .get(0);

        assertEquals(Duration.ofSeconds(20), passage.elapsed());
    }

    @Test
    public void liveTeamAssignmentResolvesReserveRegistration() {
        Fixture fixture = fixture();
        fixture.assignTeam("R0001", "0042");
        fixture.setStart("0042", Instant.parse("2026-10-13T12:00:00Z"));

        StageTimingRecord passage =
                stageTimes(
                                fixture.raceData,
                                active(
                                        Collections.<TimingData>singletonList(
                                                automatic(
                                                        1L,
                                                        "R0001",
                                                        Instant.parse("2026-10-13T12:00:30Z"),
                                                        TimingData.RegistrationAction.ADD))))
                        .get(0);

        assertEquals(new TeamId("0042"), passage.teamId());
        assertEquals(new RegistrationId("R0001"), passage.registrationId());
        assertEquals(Duration.ofSeconds(30), passage.elapsed());
    }

    @Test
    public void revokedAndUnresolvedRegistrationsDoNotBecomeStageTimingRecords() {
        Fixture fixture = fixture();
        fixture.setStart("0001", Instant.parse("2026-10-13T12:00:00Z"));

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

        assertTrue(stageTimes(fixture.raceData, active(Arrays.asList(add, lifecycle, revoke, unresolved))).isEmpty());
    }

    @Test
    public void currentAbsoluteStartStateRecalculatesPassageWithoutChangingTimingData() {
        Fixture fixture = fixture();
        TimingData record =
                automatic(
                        1L,
                        "A0001",
                        Instant.parse("2026-10-13T12:01:00Z"),
                        TimingData.RegistrationAction.ADD);
        ActiveRegistrations active = active(Collections.singletonList(record));

        fixture.setStart("0001", Instant.parse("2026-10-13T12:00:00Z"));
        StageTimingRecord first = stageTimes(fixture.raceData, active).get(0);

        fixture.setStart("0001", Instant.parse("2026-10-13T12:00:30Z"));
        StageTimingRecord second = stageTimes(fixture.raceData, active).get(0);

        assertEquals(Instant.parse("2026-10-13T12:00:00Z"), first.startedAt().instant());
        assertEquals(Instant.parse("2026-10-13T12:00:30Z"), second.startedAt().instant());
        assertEquals(first.finishedAt(), second.finishedAt());
        assertEquals(Duration.ofSeconds(60), first.elapsed());
        assertEquals(Duration.ofSeconds(30), second.elapsed());
        assertEquals(1L, record.sequenceNumber());
    }

    /** NodeService receives local IF-06 time but exposes only normalized absolute timing. */
    @Test
    public void nodeQueryUsesStageStartNormalizedAtRaceDataIngress() {
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

            List<StageTimingRecord> passages = stageTimes(components.nodeService());

            assertEquals(1, passages.size());
            assertEquals(Instant.parse("2026-10-13T12:00:00Z"), passages.get(0).startedAt().instant());
            assertEquals(Duration.ofSeconds(20), passages.get(0).elapsed());
        } finally {
            deactivate(node);
        }
    }

    /** Collects test results from already-normalized RaceDataState. */
    private static List<StageTimingRecord> stageTimes(
            RaceDataState raceData,
            ActiveRegistrations activeRegistrations) {

        List<StageTimingRecord> result = new ArrayList<StageTimingRecord>();
        for (ActiveRegistrations.Registration registration : activeRegistrations.values()) {
            TeamId teamId = raceData.teamIdFor(registration.registrationId());
            Long startMillis = teamId == null ? null : raceData.stageStartTimeMillisFor(teamId);
            if (registration.isValid() && startMillis != null) {
                result.add(StageTiming.resolve(registration, teamId, startMillis.longValue()));
            }
        }
        return result;
    }

    /** Collects a small bounded NodeService passage result for assertions only. */
    private static List<StageTimingRecord> stageTimes(NodeService nodeService) {

        List<StageTimingRecord> result = new ArrayList<StageTimingRecord>();
        nodeService.visitStageTimingRecords(16, result::add);
        return result;
    }

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

        private void setStart(String teamId, Instant start) {

            raceData.stageStartTimes().set(new TeamId(teamId), start.toEpochMilli());
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
