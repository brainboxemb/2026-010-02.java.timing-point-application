package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;

import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTimingRecord;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.RegistrationRank;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.RegisteredTeams;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;

public class ActiveRegistrationsIntegrationTest {

    /** Verifies recovered registrations are recounted against current team assignments. */
    @Test
    public void recoveredAndLaterAssignedRegistrationsUseCurrentTeamResolution() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registration(1L, "A0001"),
                                registration(2L, "R0001")),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream);
        TimingNode node =
                components.node();

        List<RegisteredTeams.Change> teamChanges = Collections.synchronizedList(new ArrayList<>());
        components.nodeService().REGISTERED_TEAMS_CHANGED.subscribe(teamChanges::add);

        activate(node);
        try {
            awaitOperational(node);
            UpstreamHandler port =
                    components.upstreamHandler();

            // A0001 is fixed by EventData. R0001 is still unresolved.
            awaitUniqueTeamCount(
                    components.nodeService(),
                    new LocationId(1),
                    1);
            List<TeamId> initialTeams = new ArrayList<>();
            assertEquals(1, components.nodeService().visitRegisteredTeams(new LocationId(1), initialTeams::add));
            assertEquals(new TeamId("0001"), initialTeams.get(0));

            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "R0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));
            org.junit.Assert.assertTrue(
                    port.inputAvailable());
            stream.takeSent();

            // The LogBook is unchanged; the current assignment now resolves the
            // second active RegistrationId to a second unique TeamId.
            awaitUniqueTeamCount(
                    components.nodeService(),
                    new LocationId(1),
                    2);
            List<TeamId> synchronizedTeams = new ArrayList<>();
            assertEquals(2, components.nodeService().visitRegisteredTeams(new LocationId(1), synchronizedTeams::add));
            org.junit.Assert.assertTrue(synchronizedTeams.contains(new TeamId("0001")));
            org.junit.Assert.assertTrue(synchronizedTeams.contains(new TeamId("0042")));
            assertEquals(2, teamChanges.size());
            assertEquals(RegisteredTeams.Change.Action.ADDED, teamChanges.get(1).action());
            assertEquals(new TeamId("0042"), teamChanges.get(1).teamId());
            assertEquals(
                    Integer.valueOf(2),
                    node.logBook().process(
                            LogBookQueries.timingDataCount()));
        } finally {
            deactivate(node);
        }
    }


    /** Duplicate passages remain visible and both receive their own ranking positions. */
    @Test
    public void duplicatePassagesAreRankedIndividually() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registrationAt(
                                        1L,
                                        "A0001",
                                        Instant.parse("2026-10-13T12:00:20Z")),
                                registrationAt(
                                        2L,
                                        "A0001",
                                        Instant.parse("2026-10-13T12:00:10Z"))),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream);

        activate(components.node());
        try {
            awaitOperational(components.node());

            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                            Collections.singletonList(
                                    DefaultUpstreamData.stageStartSet("0001", LocalTime.NOON))));
            org.junit.Assert.assertTrue(components.upstreamHandler().inputAvailable());
            stream.takeSent();

            List<ActiveRegistrations.Registration> firstPage =
                    new ArrayList<>();
            components.nodeService().visitRegistrationsAfter(new LocationId(1), 0L, 1, firstPage::add);
            assertEquals(1L, firstPage.get(0).sequenceNumber());

            List<ActiveRegistrations.Registration> secondPage =
                    new ArrayList<>();
            components.nodeService().visitRegistrationsAfter(
                    new LocationId(1), firstPage.get(0).sequenceNumber(), 1, secondPage::add);
            assertEquals(2L, secondPage.get(0).sequenceNumber());

            List<StageTimingRecord> passages = new ArrayList<StageTimingRecord>();
            components.nodeService().visitStageTimingRecords(ZoneOffset.UTC, 16, passages::add);

            List<RegistrationRank> ranking = new ArrayList<RegistrationRank>();
            components.nodeService()
                    .visitRegistrationRanking(
                            new LocationId(1),
                            ZoneOffset.UTC,
                            16,
                            ranking::add);

            assertEquals(2, passages.size());
            assertEquals(2, ranking.size());
            assertEquals(2L, ranking.get(0).timingRecord().sequenceNumber());
            assertEquals(1L, ranking.get(1).timingRecord().sequenceNumber());
            assertEquals(1, ranking.get(0).rank());
            assertEquals(2, ranking.get(1).rank());
        } finally {
            deactivate(components.node());
        }
    }

    /** Verifies recovered TimingData larger than one batch is read completely. */
    @Test
    public void recoveredTimingDataIsReadInBoundedBatches() {
        TimingData[] records = new TimingData[1000];
        for (int index = 0; index < records.length - 1; index++) {
            records[index] =
                    registration(
                            index + 1L,
                            "A0001");
        }
        records[records.length - 1] =
                registration(
                        records.length,
                        "A0002");

        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(records),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        new InMemoryUpstreamStream());

        activate(components.node());
        try {
            awaitOperational(components.node());
            awaitUniqueTeamCount(
                    components.nodeService(),
                    new LocationId(1),
                    2);
        } finally {
            deactivate(components.node());
        }
    }

    /** Waits until recovered Task Lane state is available. */
    private static void awaitOperational(TimingNode node) {
        long deadline =
                System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L);
        while (node.operationalState()
                        == TimingNodeTypes.OperationalState.INIT
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(
                TimingNodeTypes.OperationalState.OK,
                node.operationalState());
    }


    /** Waits until NodeService has caught up to the published LogBook head. */
    private static void awaitUniqueTeamCount(
            NodeService service,
            LocationId locationId,
            int expected) {

        assertEquals(
                expected,
                service.uniqueTeamRegistrationCount(locationId));
    }

    /** Builds one persisted automatic registration for the recovery fixture. */
    private static TimingData registration(long sequence, String registrationId) {

        Instant effectiveTime =
                Instant.parse("2026-10-13T12:00:00Z").plusMillis(sequence);
        return registrationAt(sequence, registrationId, effectiveTime);
    }

    /** Builds one persisted automatic registration at an explicit effective time. */
    private static TimingData registrationAt(
            long sequence,
            String registrationId,
            Instant effectiveTime) {

        TimingTimestamp time = new TimingTimestamp(effectiveTime);
        return new DefaultTimingDataFactory()
                .createAutomaticRegistration(
                        new TimingDataFactory.Context(
                                new NodeId("A"),
                                sequence,
                                new LocationId(1),
                                time,
                                time),
                        new RegistrationId(registrationId));
    }

    private static final class StaticPersistence
            implements TimingDataPersistence {
        private final List<TimingData> records;

        /** Creates immutable persistence containing the supplied recovered records. */
        private StaticPersistence(
                TimingData... records) {
            this.records =
                    Arrays.asList(records);
        }

        /** Returns the configured persisted records without repair. */
        @Override
        public LoadResult load() {
            return new LoadResult(
                    records,
                    false);
        }

        /** Rejects commits because this test only verifies recovery and live assignment changes. */
        @Override
        public void append(TimingData data) {
            throw new AssertionError(
                    "recovery/count test must not append TimingData");
        }
    }
}
