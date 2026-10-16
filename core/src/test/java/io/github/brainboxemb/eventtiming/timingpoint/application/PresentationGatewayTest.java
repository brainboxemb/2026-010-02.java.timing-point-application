package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

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
        TimingNode node = node(new RecordingStore());
        PresentationGateway gateway = new PresentationGateway(identity, nodes(node), configuration());

        assertSame(identity, gateway.version());
    }

    @Test
    public void statusComesFromTimingNode() throws Exception {
        TimingNode node = node(new RecordingStore());
        PresentationGateway gateway = new PresentationGateway(identity(), nodes(node), configuration());

        node.activate();
        try {
            TimingNodeStatus status = awaitStatus(gateway.timingNode(new NodeId("A")));
            assertEquals(new NodeId("A"), status.timingNodeId());
            assertEquals(
                    TimingNodeTypes.State.CLOSED,
                    status.state());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void statusMapsContainedRecoveryProblemFromTimingNode() throws Exception {
        RecordingStore store = new RecordingStore();
        store.failLoad = true;
        TimingNode node = node(store);
        PresentationGateway gateway = new PresentationGateway(identity(), nodes(node), configuration());

        node.activate();
        try {
            TimingNodeStatus status = awaitErrorStatus(gateway.timingNode(new NodeId("A")));
            assertTrue(status.error());
            assertEquals(
                    TimingNodeTypes.ErrorCode.TIMING_DATA_LOAD_FAILED,
                    status.errorCode());
            assertTrue(status.errorMessage().contains(
                    "expected persisted-data load failure"));
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void timingNodeProxyOwnsNodeScopedPresentationBoundary() throws Exception {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        PresentationGateway gateway = new PresentationGateway(identity(), nodes(node), configuration());
        TimingNodeProxy proxy = gateway.timingNode(new NodeId("A"));
        List<TimingNodeStatus> statusChanges = new ArrayList<>();
        List<TimingData> committed = new ArrayList<>();

        node.activate();
        try {
            awaitStatus(proxy);
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
                    TimingNodeTypes.State.OPEN,
                    statusChanges.get(0).state());
            assertEquals(
                    new LocationId(24),
                    statusChanges.get(0).locationId());
            assertEquals(1, committed.size());
            assertTrue(committed.get(0) instanceof TimingData.NodeOpen);

            TimingNodeTypes.RegistrationResult registration =
                    proxy.addCustomAutomaticRegistration(
                            new RegistrationId("N0001"),
                            TIME,
                            TimingData.AutomaticTimeSource.API);
            assertTrue(registration.committed());
            assertEquals(2, committed.size());
            assertSame(registration.timingData(), committed.get(1));
            assertEquals(2, proxy.logBookCount());

            List<TimingData> visited = new ArrayList<>();
            assertEquals(
                    2,
                    proxy.visitLogBookFrom(
                            1L,
                            10,
                            visited::add));
            assertEquals(2, visited.size());
            assertTrue(visited.get(0) instanceof TimingData.NodeOpen);
            assertSame(registration.timingData(), visited.get(1));

            visited.clear();
            assertEquals(
                    2,
                    proxy.visitLatestLogBook(
                            10,
                            visited::add));
            assertEquals(2, visited.size());
            assertSame(registration.timingData(), visited.get(1));

            assertEquals(TimingNodeTypes.CloseResult.CLOSED, proxy.close());
            assertEquals(2, statusChanges.size());
            assertEquals(
                    TimingNodeTypes.State.CLOSED,
                    statusChanges.get(1).state());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void exposesOneProxyPerComposedTimingNode() throws Exception {
        TimingNode first =
                TimingNodeFixture.create(
                        new NodeId("A"),
                        new RecordingStore(),
                        () -> RECORDED_AT.instant());
        TimingNode second =
                TimingNodeFixture.create(
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

        first.activate();
        second.activate();
        try {
            awaitStatus(gateway.timingNode(new NodeId("A")));
            awaitStatus(gateway.timingNode(new NodeId("B")));
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
            second.deactivate();
            first.deactivate();
        }
    }

    @Test
    public void rejectsDuplicateApplicationWideTimingNodeId() {
        TimingNode first =
                TimingNodeFixture.create(
                        new NodeId("A"),
                        new RecordingStore(),
                        () -> RECORDED_AT.instant());
        TimingNode second =
                TimingNodeFixture.create(
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
        TimingNode node =
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

    private static TimingNodeStatus awaitErrorStatus(
            TimingNodeProxy proxy)
            throws Exception {
        long deadline =
                System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L);
        while (true) {
            TimingNodeStatus status = proxy.status();
            if (status.error()) {
                return status;
            }
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("TimingNode error status did not become available");
            }
            Thread.sleep(2L);
        }
    }

    private static TimingNodeStatus awaitStatus(
            TimingNodeProxy proxy)
            throws Exception {
        long deadline =
                System.nanoTime()
                        + java.util.concurrent.TimeUnit.SECONDS.toNanos(1L);
        while (true) {
            try {
                return proxy.status();
            } catch (OperationException ex) {
                if (ex.reason() != OperationException.Reason.UNAVAILABLE) {
                    throw ex;
                }
                if (System.nanoTime() >= deadline) {
                    throw ex;
                }
                Thread.sleep(2L);
            }
        }
    }

    private static TimingNodeList nodes(
            TimingNode... timingNodes) {

        TimingNodeList.Builder result = TimingNodeList.builder();
        for (TimingNode timingNode : timingNodes) {
            result.add(timingNode);
        }
        return result.build();
    }

    private static ConfigurationControl configuration() {
        return PresentationGatewayFixture.configurationControl(
                new NodeId("A"));
    }

    private static TimingNode node(RecordingStore store) {
        return TimingNodeFixture.create(
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
        private final List<TimingData> appended = new ArrayList<>();
        private boolean failLoad;

        @Override
        public LoadResult load() throws PersistenceException {
            if (failLoad) {
                throw new PersistenceException("expected persisted-data load failure");
            }
            return new LoadResult(Collections.<TimingData>emptyList(), false);
        }

        @Override
        public void append(TimingData data) {
            appended.add(data);
        }
    }
}
