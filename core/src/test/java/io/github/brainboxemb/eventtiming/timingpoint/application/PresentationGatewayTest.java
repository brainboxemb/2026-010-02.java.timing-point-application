package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PresentationGatewayTest {
    private static final TimingTimestamp TIME =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Test
    public void versionReturnsAuthoritativeBuildIdentity() {
        BuildIdentity identity = identity();
        TimingNode node = node(new RecordingStore());
        PresentationGateway gateway = new PresentationGateway(identity, node);

        assertSame(identity, gateway.version());
    }

    @Test
    public void statusComesFromTimingNode() {
        TimingNode node = node(new RecordingStore());
        PresentationGateway gateway = new PresentationGateway(identity(), node);

        node.start();
        try {
            TimingNodeStatus status = gateway.timingNode().status();
            assertEquals(new NodeId("TN-01"), status.timingNodeId());
            assertEquals(
                    TimingNodeTypes.Lifecycle.CLOSED,
                    status.lifecycle());
        } finally {
            node.stop();
        }
    }

    @Test
    public void statusMapsContainedRecoveryProblemFromTimingNode() {
        RecordingStore store = new RecordingStore();
        store.failLoad = true;
        TimingNode node = node(store);
        PresentationGateway gateway = new PresentationGateway(identity(), node);

        node.start();
        try {
            TimingNodeStatus status = gateway.timingNode().status();
            assertEquals(
                    TimingNodeTypes.Lifecycle.ERROR,
                    status.lifecycle());
            assertEquals(1, status.problems().size());
            assertEquals(
                    TimingNodeTypes.ProblemCode.TIMING_DATA_RECOVERY_FAILED,
                    status.problems().get(0).code());
            assertTrue(status.problems().get(0).message().contains(
                    "expected recovery failure"));
        } finally {
            node.stop();
        }
    }

    @Test
    public void timingNodeProxyOwnsNodeScopedPresentationBoundary() {
        RecordingStore store = new RecordingStore();
        TimingNode node = node(store);
        PresentationGateway gateway = new PresentationGateway(identity(), node);
        TimingNodeProxy proxy = gateway.timingNode();
        List<TimingNodeStatus> statusChanges = new ArrayList<>();
        List<TimingData> committed = new ArrayList<>();

        node.start();
        try {
            proxy.statusChangedEvent().subscribe(statusChanges::add);
            proxy.timingDataCommittedEvent().subscribe(committed::add);

            PresentationGateway.Capabilities capabilities = gateway.capabilities();
            assertTrue(capabilities.directRegistrationSimulationSupported());
            assertTrue(capabilities.directRegistrationSimulationEnabled());

            assertFalse(proxy.status().hasLocation());

            assertEquals(
                    TimingNodeTypes.OpenResult.OPENED,
                    proxy.open(new LocationId(24)));
            assertEquals(1, statusChanges.size());
            assertEquals(
                    TimingNodeTypes.Lifecycle.OPEN,
                    statusChanges.get(0).lifecycle());
            assertEquals(
                    new LocationId(24),
                    statusChanges.get(0).locationId());

            TimingNodeTypes.RegistrationResult registration =
                    proxy.applyAutomaticRegistration(
                            TimingNodeProxy.AutomaticRegistrationAction.ADD,
                            new RegistrationId("N0001"),
                            TIME);
            assertTrue(registration.committed());
            assertEquals(1, committed.size());
            assertSame(registration.timingData(), committed.get(0));
            assertEquals(1, proxy.logBookCount());

            List<TimingData> visited = new ArrayList<>();
            assertEquals(
                    1,
                    proxy.visitLogBookFrom(
                            1L,
                            10,
                            visited::add));
            assertEquals(1, visited.size());
            assertSame(registration.timingData(), visited.get(0));

            visited.clear();
            assertEquals(
                    1,
                    proxy.visitLatestLogBook(
                            10,
                            visited::add));
            assertEquals(1, visited.size());
            assertSame(registration.timingData(), visited.get(0));

            assertEquals(TimingNodeTypes.CloseResult.CLOSED, proxy.close());
            assertEquals(2, statusChanges.size());
            assertEquals(
                    TimingNodeTypes.Lifecycle.CLOSED,
                    statusChanges.get(1).lifecycle());
        } finally {
            node.stop();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingBuildIdentity() {
        new PresentationGateway(
                null,
                node(new RecordingStore()),
                PresentationGatewayFixture.configurationControl(
                        new NodeId("TN-01")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimingNode() {
        new PresentationGateway(
                identity(),
                null,
                PresentationGatewayFixture.configurationControl(
                        new NodeId("TN-01")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingConfigurationControl() {
        new PresentationGateway(
                identity(),
                node(new RecordingStore()),
                null);
    }

    private static TimingNode node(RecordingStore store) {
        return TimingNodeFixture.create(
                new NodeId("TN-01"),
                store,
                () -> RECORDED_AT);
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
                throw new PersistenceException("expected recovery failure");
            }
            return new LoadResult(Collections.<TimingData>emptyList(), false);
        }

        @Override
        public void append(TimingData data) {
            appended.add(data);
        }
    }
}
