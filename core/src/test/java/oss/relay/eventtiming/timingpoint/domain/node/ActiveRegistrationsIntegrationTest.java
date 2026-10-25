package oss.relay.eventtiming.timingpoint.domain.node;

import oss.relay.eventtiming.eventdata.TeamId;
import oss.relay.eventtiming.eventdata.defaultprofile.DefaultEventData;
import oss.relay.eventtiming.timingdata.TimingDataTypes.LocationId;
import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.eventtiming.timingdata.TimingRecord;
import oss.relay.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import oss.relay.eventtiming.timingpoint.domain.node.registration.RegisteredTeams;
import oss.relay.eventtiming.timingpoint.domain.node.service.NodeService;
import oss.relay.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import oss.relay.eventtiming.upstreamdata.UpstreamData;
import oss.relay.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;

/** Verifies active-WP registration reconstruction and live team projection behavior. */
public class ActiveRegistrationsIntegrationTest {

    @Test
    public void recoveredAndLaterAssignedRegistrationsUseCurrentTeamResolution() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registration(1L, 1, "A0001"),
                                registration(2L, 1, "R0001")),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream.bind(
                                new NodeId("A")));
        TimingNode node = components.node();

        List<RegisteredTeams.Change> teamChanges =
                Collections.synchronizedList(new ArrayList<RegisteredTeams.Change>());
        components.nodeService().state().registeredTeamsChanged().subscribe(teamChanges::add);

        activate(node);
        activate(components.nodeUpstream());
        try {
            awaitOperational(node);
            assertEquals(TimingNodeTypes.OpenResult.OPENED, node.open(new LocationId(1)));

            awaitUniqueTeamCount(components.nodeService(), 1);
            assertEquals(0, teamChanges.size());

            List<TeamId> initialTeams = new ArrayList<>();
            assertEquals(1, components.nodeService().state().visitRegisteredTeams(initialTeams::add));
            assertEquals(new TeamId("0001"), initialTeams.get(0));

            stream.receive(
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            1L,
                            DefaultUpstreamData.createRaceData(
                                    new LocationId(1),
                                    Collections.singletonList(
                                            DefaultUpstreamData.createTeamAssignmentSet(
                                                    "R0001",
                                                    "0042")),
                                    Collections.<UpstreamData.StageStartTimeChange>emptyList())));
            stream.takeSent();

            awaitUniqueTeamCount(components.nodeService(), 2);
            List<TeamId> synchronizedTeams = new ArrayList<>();
            assertEquals(2, components.nodeService().state().visitRegisteredTeams(synchronizedTeams::add));
            org.junit.Assert.assertTrue(synchronizedTeams.contains(new TeamId("0001")));
            org.junit.Assert.assertTrue(synchronizedTeams.contains(new TeamId("0042")));
            assertEquals(1, teamChanges.size());
            assertEquals(RegisteredTeams.Change.Action.ADDED, teamChanges.get(0).action());
            assertEquals(new TeamId("0042"), teamChanges.get(0).teamId());
        } finally {
            deactivate(components.nodeUpstream());
            deactivate(node);
        }
    }

    @Test
    public void openingWpRebuildsOnlyThatLocationAcrossEarlierOpenPeriods() {
        TimingRecord[] history =
                new TimingRecord[] {
                    opened(1L, 1),
                    registration(2L, 1, "A0001"),
                    closed(3L, 1),
                    opened(4L, 2),
                    registration(5L, 2, "A0002"),
                    closed(6L, 2)
                };
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(history),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        new InMemoryUpstreamStream()
                                .bind(
                                        new NodeId("A")));
        TimingNode node = components.node();

        activate(node);
        try {
            awaitOperational(node);

            assertEquals(TimingNodeTypes.OpenResult.OPENED, node.open(new LocationId(1)));
            assertSequences(components.nodeService(), 2L);

            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.close());
            assertEquals(TimingNodeTypes.OpenResult.OPENED, node.open(new LocationId(2)));
            assertSequences(components.nodeService(), 5L);

            assertEquals(TimingNodeTypes.CloseResult.CLOSED, node.close());
            assertEquals(TimingNodeTypes.OpenResult.OPENED, node.open(new LocationId(1)));
            assertSequences(components.nodeService(), 2L);
        } finally {
            deactivate(node);
        }
    }

    @Test
    public void duplicatePassagesRemainSeparateRegistrationRows() {
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registrationAt(
                                        1L,
                                        1,
                                        "A0001",
                                        Instant.parse("2026-10-13T12:00:20Z")),
                                registrationAt(
                                        2L,
                                        1,
                                        "A0001",
                                        Instant.parse("2026-10-13T12:00:10Z"))),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        new InMemoryUpstreamStream()
                                .bind(
                                        new NodeId("A")));

        activate(components.node());
        try {
            awaitOperational(components.node());
            components.node().open(new LocationId(1));

            List<ActiveRegistrations.Registration> rows = new ArrayList<>();
            assertEquals(2, components.nodeService().state().visitRegistrations(2, rows::add));
            assertEquals(1L, rows.get(0).sequenceNumber());
            assertEquals(2L, rows.get(1).sequenceNumber());
        } finally {
            deactivate(components.node());
        }
    }

    @Test
    public void raceDataStageStartsUpdateRecoveredRuntimeAndRanking() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registrationAt(
                                        1L,
                                        1,
                                        "A0001",
                                        Instant.parse("2026-10-13T12:00:20Z")),
                                registrationAt(
                                        2L,
                                        1,
                                        "A0002",
                                        Instant.parse("2026-10-13T12:00:10Z"))),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream.bind(
                                new NodeId("A")));

        activate(components.node());
        activate(components.nodeUpstream());
        try {
            awaitOperational(components.node());
            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    components.node().open(new LocationId(1)));

            stream.receive(
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            1L,
                            DefaultUpstreamData.createRaceData(
                                    new LocationId(1),
                                    Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                                    Arrays.asList(
                                            DefaultUpstreamData.createStageStartSet(
                                                    "A0001",
                                                    java.time.LocalTime.NOON),
                                            DefaultUpstreamData.createStageStartSet(
                                                    "A0002",
                                                    java.time.LocalTime.NOON)))));
            stream.takeSent();

            List<ActiveRegistrations.Registration> rows = new ArrayList<>();
            components.nodeService().state().visitRegistrations(10, rows::add);
            assertEquals(20000L, rows.get(0).elapsedMillis());
            assertEquals(2, rows.get(0).rank());
            assertEquals(10000L, rows.get(1).elapsedMillis());
            assertEquals(1, rows.get(1).rank());

            stream.receive(
                    DefaultUpstreamData.createDnPacket(
                            new NodeId("A"),
                            2L,
                            DefaultUpstreamData.createRaceData(
                                    new LocationId(1),
                                    Collections.<UpstreamData.TeamAssignmentChange>emptyList(),
                                    Collections.singletonList(
                                            DefaultUpstreamData.createStageStartSet(
                                                    "A0001",
                                                    java.time.LocalTime.of(
                                                            12,
                                                            0,
                                                            15))))));
            stream.takeSent();

            assertEquals(5000L, rows.get(0).elapsedMillis());
            assertEquals(1, rows.get(0).rank());
            assertEquals(2, rows.get(1).rank());
        } finally {
            deactivate(components.nodeUpstream());
            deactivate(components.node());
        }
    }

    @Test
    public void recoveredTimingDataLargerThanOneBatchIsReadCompletely() {
        TimingRecord[] records = new TimingRecord[1000];
        for (int index = 0; index < records.length - 1; index++) {
            records[index] = registration(index + 1L, 1, "A0001");
        }
        records[records.length - 1] = registration(records.length, 1, "A0002");

        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(records),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        new InMemoryUpstreamStream()
                                .bind(
                                        new NodeId("A")));

        activate(components.node());
        try {
            awaitOperational(components.node());
            components.node().open(new LocationId(1));
            awaitUniqueTeamCount(components.nodeService(), 2);
        } finally {
            deactivate(components.node());
        }
    }

    private static void awaitOperational(TimingNode node) {
        long deadline =
                System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L);
        while (node.operationalState() == TimingNodeTypes.OperationalState.INIT
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(TimingNodeTypes.OperationalState.OK, node.operationalState());
    }

    private static void awaitUniqueTeamCount(NodeService service, int expected) {
        assertEquals(expected, service.state().uniqueTeamRegistrationCount());
    }

    private static void assertSequences(NodeService service, long... expected) {
        List<ActiveRegistrations.Registration> rows = new ArrayList<>();
        assertEquals(expected.length, service.state().visitRegistrations(expected.length + 1, rows::add));
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], rows.get(index).sequenceNumber());
        }
    }

    private static TimingRecord registration(long sequence, int location, String registrationId) {
        Instant effectiveTime = Instant.parse("2026-10-13T12:00:00Z").plusMillis(sequence);
        return registrationAt(sequence, location, registrationId, effectiveTime);
    }

    private static TimingRecord registrationAt(
            long sequence,
            int location,
            String registrationId,
            Instant effectiveTime) {
        long millis = effectiveTime.toEpochMilli();
        return TimingRecord.createAutomaticRegistration(
                'A',
                sequence,
                location,
                millis,
                millis,
                new RegistrationId(registrationId).packedValue(),
                TimingRecord.Action.ADD,
                null,
                null);
    }

    private static TimingRecord opened(long sequence, int location) {
        long millis =
                Instant.parse("2026-10-13T12:00:00Z")
                        .plusMillis(sequence)
                        .toEpochMilli();
        return TimingRecord.createNodeOpened('A', sequence, location, millis, millis);
    }

    private static TimingRecord closed(long sequence, int location) {
        long millis =
                Instant.parse("2026-10-13T12:00:00Z")
                        .plusMillis(sequence)
                        .toEpochMilli();
        return TimingRecord.createNodeClosed('A', sequence, location, millis, millis);
    }

    private static final class StaticPersistence implements TimingDataPersistence {
        private final List<TimingRecord> records;

        private StaticPersistence(TimingRecord... records) {
            this.records = Arrays.asList(records);
        }

        @Override
        public LoadResult load() {
            return new LoadResult(records, false);
        }

        @Override
        public void append(TimingRecord record) {
            // Runtime OPEN/CLOSE records are accepted; this fixture verifies reconstruction.
        }
    }
}
