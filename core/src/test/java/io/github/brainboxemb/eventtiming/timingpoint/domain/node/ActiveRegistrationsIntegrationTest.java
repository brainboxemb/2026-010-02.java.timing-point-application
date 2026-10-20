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
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.ActiveRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration.RegisteredTeams;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;

/** Verifies active-WP registration reconstruction and live team projection behavior. */
public class ActiveRegistrationsIntegrationTest {

    /** Historical rebuild is silent; a later assignment change produces one live team event. */
    @Test
    public void recoveredAndLaterAssignedRegistrationsUseCurrentTeamResolution() {
        InMemoryUpstreamStream stream = new InMemoryUpstreamStream();
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(
                                registration(1L, 1, "A0001"),
                                registration(2L, 1, "R0001")),
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        stream);
        TimingNode node = components.node();

        List<RegisteredTeams.Change> teamChanges = Collections.synchronizedList(new ArrayList<>());
        components.nodeService().REGISTERED_TEAMS_CHANGED.subscribe(teamChanges::add);

        activate(node);
        try {
            awaitOperational(node);
            assertEquals(TimingNodeTypes.OpenResult.OPENED, node.open(new LocationId(1)));

            awaitUniqueTeamCount(components.nodeService(), 1);
            assertEquals(0, teamChanges.size());

            List<TeamId> initialTeams = new ArrayList<>();
            assertEquals(1, components.nodeService().visitRegisteredTeams(initialTeams::add));
            assertEquals(new TeamId("0001"), initialTeams.get(0));

            UpstreamHandler port = components.upstreamHandler();
            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "R0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));
            org.junit.Assert.assertTrue(port.inputAvailable());
            stream.takeSent();

            awaitUniqueTeamCount(components.nodeService(), 2);
            List<TeamId> synchronizedTeams = new ArrayList<>();
            assertEquals(2, components.nodeService().visitRegisteredTeams(synchronizedTeams::add));
            org.junit.Assert.assertTrue(synchronizedTeams.contains(new TeamId("0001")));
            org.junit.Assert.assertTrue(synchronizedTeams.contains(new TeamId("0042")));
            assertEquals(1, teamChanges.size());
            assertEquals(RegisteredTeams.Change.Action.ADDED, teamChanges.get(0).action());
            assertEquals(new TeamId("0042"), teamChanges.get(0).teamId());
        } finally {
            deactivate(node);
        }
    }

    /** Only the open WP is projected, and reopening an older WP rebuilds its old rows. */
    @Test
    public void openingWpRebuildsOnlyThatLocationAcrossEarlierOpenPeriods() {
        DefaultTimingDataFactory factory = new DefaultTimingDataFactory();
        TimingData[] history =
                new TimingData[] {
                    factory.createNodeOpen(context(1L, 1)),
                    registration(2L, 1, "A0001"),
                    factory.createNodeClose(context(3L, 1)),
                    factory.createNodeOpen(context(4L, 2)),
                    registration(5L, 2, "A0002"),
                    factory.createNodeClose(context(6L, 2))
                };
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        new NodeId("A"),
                        new StaticPersistence(history),
                        factory,
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        new InMemoryUpstreamStream());
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

    /** Duplicate passages of the active WP remain separate chronological rows. */
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
                        new DefaultTimingDataFactory(),
                        () -> Instant.parse("2026-10-13T12:05:00Z"),
                        new DefaultEventData(),
                        new InMemoryUpstreamStream());

        activate(components.node());
        try {
            awaitOperational(components.node());
            components.node().open(new LocationId(1));

            List<ActiveRegistrations.Registration> rows = new ArrayList<>();
            assertEquals(2, components.nodeService().visitRegistrations(2, rows::add));
            assertEquals(1L, rows.get(0).sequenceNumber());
            assertEquals(2L, rows.get(1).sequenceNumber());
        } finally {
            deactivate(components.node());
        }
    }

    /** Historical reconstruction remains bounded while filtering to the active WP. */
    @Test
    public void recoveredTimingDataLargerThanOneBatchIsReadCompletely() {
        TimingData[] records = new TimingData[1000];
        for (int index = 0; index < records.length - 1; index++) {
            records[index] = registration(index + 1L, 1, "A0001");
        }
        records[records.length - 1] = registration(records.length, 1, "A0002");

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
            components.node().open(new LocationId(1));
            awaitUniqueTeamCount(components.nodeService(), 2);
        } finally {
            deactivate(components.node());
        }
    }

    /** Waits until recovered LogBook data is available to the node. */
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

    /** Reads through NodeService, which blocks until the active WP projection is caught up. */
    private static void awaitUniqueTeamCount(NodeService service, int expected) {

        assertEquals(expected, service.uniqueTeamRegistrationCount());
    }

    /** Verifies the active chronological projection contains exactly the expected sequences. */
    private static void assertSequences(NodeService service, long... expected) {

        List<ActiveRegistrations.Registration> rows = new ArrayList<>();
        assertEquals(expected.length, service.visitRegistrations(expected.length + 1, rows::add));
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], rows.get(index).sequenceNumber());
        }
    }

    private static TimingData registration(long sequence, int location, String registrationId) {

        Instant effectiveTime = Instant.parse("2026-10-13T12:00:00Z").plusMillis(sequence);
        return registrationAt(sequence, location, registrationId, effectiveTime);
    }

    private static TimingData registrationAt(
            long sequence,
            int location,
            String registrationId,
            Instant effectiveTime) {

        TimingTimestamp time = new TimingTimestamp(effectiveTime);
        return new DefaultTimingDataFactory()
                .createAutomaticRegistration(
                        new TimingDataFactory.Context(
                                new NodeId("A"),
                                sequence,
                                new LocationId(location),
                                time,
                                time),
                        new RegistrationId(registrationId));
    }

    private static TimingDataFactory.Context context(long sequence, int location) {

        TimingTimestamp time =
                new TimingTimestamp(Instant.parse("2026-10-13T12:00:00Z").plusMillis(sequence));
        return new TimingDataFactory.Context(
                new NodeId("A"),
                sequence,
                new LocationId(location),
                time,
                time);
    }

    private static final class StaticPersistence implements TimingDataPersistence {
        private final List<TimingData> records;

        private StaticPersistence(TimingData... records) {

            this.records = Arrays.asList(records);
        }

        @Override
        public LoadResult load() {

            return new LoadResult(records, false);
        }

        @Override
        public void append(TimingData data) {
            // Runtime OPEN/CLOSE records are accepted; this fixture verifies in-memory reconstruction.
        }
    }
}
