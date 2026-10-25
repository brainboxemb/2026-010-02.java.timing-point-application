package oss.relay.eventtiming.timingpoint.application;

import oss.relay.eventtiming.timingdata.TimingDataTypes.LocationId;
import oss.relay.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.eventtiming.timingdata.TimingRecord;
import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingdata.TimingTimestamp;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNodeTypes;
import oss.relay.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.eventtiming.timingpoint.infra.BuildIdentity;
import oss.relay.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;
import oss.relay.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PresentationGatewayTest {
    private static final TimingTimestamp TIME =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void versionReturnsAuthoritativeBuildIdentity() {
        BuildIdentity identity = identity();
        TimingNodeFixture.Components node = node(new RecordingStore());
        PresentationGateway gateway = new PresentationGateway(identity, nodes(node), configuration());

        assertSame(identity, gateway.version());
    }

    @Test
    public void statusComesFromTimingNode() {
        TimingNodeFixture.Components node = node(new RecordingStore());
        PresentationGateway gateway = new PresentationGateway(identity(), nodes(node), configuration());

        activate(node.node());
        try {
            TimingNodeStatus status = gateway.timingNode(new NodeId("A")).status();
            assertEquals(new NodeId("A"), status.timingNodeId());
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    status.state());
        } finally {
            deactivate(node.node());
        }
    }

    @Test
    public void timingNodeProxyOwnsNodeScopedPresentationBoundary() throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNodeFixture.Components node = node(store);
        PresentationGateway gateway = new PresentationGateway(identity(), nodes(node), configuration());
        TimingNodeProxy proxy = gateway.timingNode(new NodeId("A"));
        List<TimingNodeStatus> statusChanges = new ArrayList<>();
        List<TimingRecord> committed = new ArrayList<>();

        activate(node.node());
        try {
            proxy.STATUS_CHANGED.subscribe(statusChanges::add);
            proxy.NEW_DATA_ENTRY.subscribe(committed::add);

            PresentationGateway.Capabilities capabilities = gateway.capabilities();
            assertTrue(capabilities.directRegistrationSimulationSupported());
            assertTrue(capabilities.directRegistrationSimulationEnabled());
            assertTrue(capabilities.tagScenarioSimulationSupported());
            assertFalse(capabilities.tagScenarioSimulationEnabled());

            assertFalse(proxy.status().hasLocation());

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    proxy.open(new LocationId(24)));
            assertEquals(1, statusChanges.size());
            assertEquals(
                    TimingNodeTypes.FunctionalState.OPEN,
                    statusChanges.get(0).state());
            assertEquals(
                    new LocationId(24),
                    statusChanges.get(0).locationId());

            // Ordered LogBook read waits behind the asynchronous NODE_OPEN history write.
            assertEquals(1, proxy.logBookCount());
            assertEquals(1, committed.size());
            assertEquals(TimingRecord.Kind.NODE_OPEN, committed.get(0).kind());

            CommandResult registration =
                    proxy.addTestAutomaticRegistration(
                            new RegistrationId("N0001"),
                            TIME,
                            TimingRecord.AutomaticTimeSource.API);
            assertTrue(registration.committed());
            assertEquals(2, committed.size());
            assertEquals(
                    registration.timingRecord(),
                    committed.get(1));
            assertEquals(2, proxy.logBookCount());

            List<TimingRecord> visited = new ArrayList<>();
            assertEquals(
                    2,
                    proxy.visitLogBookFrom(
                            1L,
                            10,
                            visited::add));
            assertEquals(2, visited.size());
            assertEquals(TimingRecord.Kind.NODE_OPEN, visited.get(0).kind());
            assertEquals(
                    registration.timingRecord(),
                    visited.get(1));

            visited.clear();
            assertEquals(
                    2,
                    proxy.visitLatestLogBook(
                            10,
                            visited::add));
            assertEquals(2, visited.size());
            assertEquals(
                    registration.timingRecord(),
                    visited.get(1));

            assertEquals(TimingNodeTypes.CloseResult.CLOSED, proxy.close());
            assertEquals(2, statusChanges.size());
            assertEquals(
                    TimingNodeTypes.FunctionalState.CLOSED,
                    statusChanges.get(1).state());
        } finally {
            deactivate(node.node());
        }
    }

    @Test
    public void exposesOneProxyPerComposedTimingNode() throws Exception {
        TimingNodeFixture.Components first =
                TimingNodeFixture.createComponents(
                        new NodeId("A"),
                        new RecordingStore(),
                        () -> RECORDED_AT.instant());
        TimingNodeFixture.Components second =
                TimingNodeFixture.createComponents(
                        new NodeId("B"),
                        new RecordingStore(),
                        () -> RECORDED_AT.instant());

        PresentationGateway gateway =
                new PresentationGateway(
                        identity(),
                        nodes(first, second),
                        PresentationGatewayFixture.configurationControl(
                                new NodeId("A"),
                                new NodeId("B")));

        activate(first.node());
        activate(second.node());
        try {
            assertEquals(2, gateway.timingNodes().size());
            assertEquals(
                    new NodeId("A"),
                    gateway.timingNode(new NodeId("A"))
                            .timingNodeId());
            assertEquals(
                    new NodeId("B"),
                    gateway.timingNode(new NodeId("B"))
                            .timingNodeId());
            assertEquals(
                    2,
                    gateway.timingNodeStatuses().size());
        } finally {
            deactivate(second.node());
            deactivate(first.node());
        }
    }

    @Test
    public void rejectsDuplicateApplicationWideTimingNodeId() {
        TimingNodeFixture.Components first =
                TimingNodeFixture.createComponents(
                        new NodeId("A"),
                        new RecordingStore(),
                        () -> RECORDED_AT.instant());
        TimingNodeFixture.Components second =
                TimingNodeFixture.createComponents(
                        new NodeId("A"),
                        new RecordingStore(),
                        () -> RECORDED_AT.instant());

        try {
            new PresentationGateway(
                    identity(),
                    nodes(first, second),
                    configuration());
            fail("Expected duplicate TimingNode id to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                    "Duplicate TimingNode id A",
                    expected.getMessage());
        }
    }

    @Test
    public void exposesOptionalSimulatedTagControlWhenComposed() {
        TimingNodeFixture.Components node =
                node(
                        new RecordingStore());
        SimulationControl simulation =
                (registrationId, profileId) ->
                        SimulationControl.StartResult.ACCEPTED;
        PresentationGateway gateway =
                new PresentationGateway(
                        identity(),
                        nodes(node),
                        configuration(),
                        simulation);

        assertTrue(
                gateway.capabilities()
                        .tagScenarioSimulationSupported());
        assertTrue(
                gateway.capabilities()
                        .tagScenarioSimulationEnabled());
        assertSame(
                simulation,
                gateway.simulation());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsSimulationAccessWhenCapabilityIsDisabled() {
        PresentationGateway gateway =
                new PresentationGateway(
                        identity(),
                        nodes(node(new RecordingStore())),
                        configuration());
        gateway.simulation();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingBuildIdentity() {
        new PresentationGateway(
                null,
                nodes(node(new RecordingStore())),
                PresentationGatewayFixture.configurationControl(
                        new NodeId("A")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimingNode() {
        new PresentationGateway(
                identity(),
                null,
                PresentationGatewayFixture.configurationControl(
                        new NodeId("A")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingConfigurationControl() {
        new PresentationGateway(
                identity(),
                nodes(node(new RecordingStore())),
                null);
    }

    private static List<TimingNodeProxyComponents> nodes(
            TimingNodeFixture.Components... timingNodes) {

        List<TimingNodeProxyComponents> result =
                new ArrayList<TimingNodeProxyComponents>();
        for (TimingNodeFixture.Components timingNode : timingNodes) {
            result.add(timingNode.presentationComponents());
        }
        return result;
    }

    private static ConfigurationControl configuration() {
        return PresentationGatewayFixture.configurationControl(
                new NodeId("A"));
    }

    private static TimingNodeFixture.Components node(RecordingStore store) {
        return TimingNodeFixture.createComponents(
                new NodeId("A"),
                store,
                () -> RECORDED_AT.instant());
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "0.2.3-SNAPSHOT",
                "revision-one",
                "feature/test",
                "local",
                false);
    }

    private static final class RecordingStore implements TimingDataPersistence {
        private final List<TimingRecord> appended = new ArrayList<>();
        private boolean failLoad;

        @Override
        public LoadResult load() throws PersistenceException {
            if (failLoad) {
                throw new PersistenceException("expected persisted-data load failure");
            }
            return new LoadResult(Collections.<TimingRecord>emptyList(), false);
        }

        @Override
        public void append(TimingRecord record) {
            appended.add(record);
        }
    }
}
