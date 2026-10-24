package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxyComponents;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.engineering.EngineerApiConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamGateway;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebSocketEndpointTest {
    private static final TimingTimestamp TIME =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");
    private static final Clock EVENT_CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T12:00:02.000Z"), ZoneOffset.UTC);

    @Test
    public void sendsCompleteSnapshotOnConnectAndReconnectWithoutHistoryReplay()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();

        // Commit history before the WebSocket endpoint/client exists.
        fixture.handler.timingNode(new NodeId("A")).open(new LocationId(24));
        fixture.handler.timingNode(new NodeId("A")).addTestAutomaticRegistration(
                new RegistrationId("N0000"),
                TIME,
                TimingRecord.AutomaticTimeSource.API);

        WebSocketEndpoint server = new WebSocketEndpoint(
                "127.0.0.1",
                0,
                fixture.handler,
                EVENT_CLOCK);
        server.start();

        try {
            TestClient first = connect(server.boundPort());
            try {
                String snapshot = first.awaitMessage();
                assertSnapshot(snapshot, "A", "OPEN", "24");
                assertNull(first.pollMessage(250));
            } finally {
                first.closeBlocking();
            }

            TestClient second = connect(server.boundPort());
            try {
                String snapshot = second.awaitMessage();
                assertSnapshot(snapshot, "A", "OPEN", "24");
                assertNull(second.pollMessage(250));
            } finally {
                second.closeBlocking();
            }
        } finally {
            server.close();
            fixture.close();
        }
    }

    @Test
    public void upstreamRoleWebSocketCarriesPacketProtocolBidirectionally()
            throws Exception {
        NodeId nodeId = new NodeId("A");
        EngineerApiConnector connector = new EngineerApiConnector();
        UpstreamStream stream = new UpstreamStream(nodeId, connector);
        new UpstreamGateway(
                connector,
                Collections.singletonMap(nodeId, stream));

        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        nodeId,
                        new MemoryStore(),
                        () -> RECORDED_AT.instant(),
                        EventData.empty(),
                        stream);

        PresentationGateway presentation =
                new PresentationGateway(
                        identity(),
                        nodes(components),
                        PresentationGatewayFixture.configurationControl(
                                nodeId));

        LifecycleTestSupport.activate(components.node());
        LifecycleTestSupport.activate(components.nodeUpstream());

        WebSocketEndpoint server =
                new WebSocketEndpoint(
                        "127.0.0.1",
                        0,
                        presentation,
                        Collections.singletonList(connector),
                        EVENT_CLOCK);
        server.start();

        TestClient normal = connect(server.boundPort());
        TestClient upstream = connectUpstream(server.boundPort());
        try {
            assertSnapshot(
                    normal.awaitMessage(),
                    "A",
                    "CLOSED",
                    "null");
            assertSnapshot(
                    upstream.awaitMessage(),
                    "A",
                    "CLOSED",
                    "null");

            String first = upstream.awaitMessage();
            String second = upstream.awaitMessage();
            String third = upstream.awaitMessage();
            String initial =
                    String.valueOf(first)
                            + "\n"
                            + String.valueOf(second)
                            + "\n"
                            + String.valueOf(third);

            assertTrue(initial.contains("\"eventType\":\"UPSTREAM_DATA\""));
            assertTrue(initial.contains("\"type\":\"UP_PACKET\""));
            assertTrue(initial.contains("\"type\":\"RECV\""));
            assertTrue(initial.contains("\"rcvPckNr\":0"));
            assertTrue(initial.contains("\"type\":\"SEQ\""));
            assertTrue(initial.contains("\"op\":\"NTF\""));
            assertTrue(initial.contains("\"seq\":0"));
            assertTrue(initial.contains("\"type\":\"PING\""));
            assertTrue(initial.contains("\"pckNr\":0"));
            assertTrue(initial.contains("\"op\":\"REQ\""));
            assertTrue(initial.contains("\"mode\":\"HB\""));

            UpstreamData racePacket =
                    DefaultUpstreamData.createDnPacket(
                            nodeId,
                            1L,
                            DefaultUpstreamData.createRaceData(
                                    new LocationId(1),
                                    Collections.singletonList(
                                            DefaultUpstreamData.createTeamAssignmentSet(
                                                    "R0001",
                                                    "0042")),
                                    Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            upstream.send(upstreamJson(racePacket));

            String recvEvent = upstream.awaitMessage();
            assertNotNull(recvEvent);
            assertTrue(recvEvent.contains("\"eventType\":\"UPSTREAM_DATA\""));
            assertTrue(recvEvent.contains("\"type\":\"UP_PACKET\""));
            assertTrue(recvEvent.contains("\"type\":\"RECV\""));
            assertTrue(recvEvent.contains("\"rcvPckNr\":1"));

            assertEquals(
                    new TeamId("0042"),
                    components.raceData().teamIdFor(
                            new RegistrationId("R0001")));

            assertNull(normal.pollMessage(250));
        } finally {
            upstream.closeBlocking();
            normal.closeBlocking();
            server.close();
            LifecycleTestSupport.deactivate(components.nodeUpstream());
            LifecycleTestSupport.deactivate(components.node());
        }
    }

    @Test
    public void broadcastsStatusChangesAndCommittedTimingDataAutomatically()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();
        WebSocketEndpoint server = new WebSocketEndpoint(
                "127.0.0.1",
                0,
                fixture.handler,
                EVENT_CLOCK);
        server.start();
        TestClient client = connect(server.boundPort());

        try {
            assertSnapshot(
                    client.awaitMessage(),
                    "A",
                    "CLOSED",
                    "null");

            fixture.handler.timingNode(new NodeId("A")).open(new LocationId(24));
            String firstOpenEvent = client.awaitMessage();
            String secondOpenEvent = client.awaitMessage();
            String openCommitted =
                    firstOpenEvent.contains("\"eventType\":\"TIMING_DATA_COMMITTED\"")
                            ? firstOpenEvent
                            : secondOpenEvent;
            String opened =
                    firstOpenEvent.contains("\"eventType\":\"STATUS_CHANGED\"")
                            ? firstOpenEvent
                            : secondOpenEvent;

            assertTrue(openCommitted.contains(
                    "\"eventType\":\"TIMING_DATA_COMMITTED\""));
            assertTrue(openCommitted.contains("\"seqNr\":1"));
            assertTrue(openCommitted.contains("\"recType\":\"NODE_INFO\""));
            assertTrue(openCommitted.contains("\"code\":[\"OPEN\"]"));

            assertTrue(opened.contains("\"eventType\":\"STATUS_CHANGED\""));
            assertTrue(opened.contains("\"locId\":24"));
            assertTrue(opened.contains("\"state\":\"OPEN\""));

            fixture.handler.timingNode(new NodeId("A")).addTestAutomaticRegistration(
                    new RegistrationId("N0001"),
                    TIME,
                    TimingRecord.AutomaticTimeSource.API);
            String committed = client.awaitMessage();
            assertTrue(committed.contains(
                    "\"eventType\":\"TIMING_DATA_COMMITTED\""));
            assertTrue(committed.contains("\"seqNr\":2"));
            assertTrue(committed.contains("\"locId\":24"));
            assertTrue(committed.contains("\"recType\":\"AUTO_REG\""));
            assertTrue(committed.contains(
                    "\"regId\":\"N0001\""));
            assertTrue(committed.contains("\"code\":[\"ADD\"]"));
            assertTrue(committed.contains(
                    "\"occurredAt\":\"2026-10-01T12:00:02.000Z\""));
        } finally {
            client.closeBlocking();
            server.close();
            fixture.close();
        }
    }

    @Test
    public void broadcastsAppliedConfigurationChangesOnly()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();
        WebSocketEndpoint server = new WebSocketEndpoint(
                "127.0.0.1",
                0,
                fixture.handler,
                EVENT_CLOCK);
        server.start();
        TestClient client = connect(server.boundPort());

        try {
            assertSnapshot(
                    client.awaitMessage(),
                    "A",
                    "CLOSED",
                    "null");

            ConfigurationControl.Update applied =
                    fixture.handler
                            .configuration()
                            .setTagProcessing(
                                    new NodeId("A"),
                                    new ConfigurationControl
                                            .TagProcessingPatch(
                                                    300L,
                                                    null,
                                                    null,
                                                    75L,
                                                    null));
            assertTrue(
                    applied.result()
                            == ConfigurationControl
                                    .UpdateResult.APPLIED);

            String changed = client.awaitMessage();
            assertNotNull(changed);
            assertTrue(changed.contains(
                    "\"eventType\":\"CONFIGURATION_CHANGED\""));
            assertTrue(changed.contains(
                    "\"occurredAt\":\"2026-10-01T12:00:02.000Z\""));
            assertTrue(changed.contains(
                    "\"nodeId\":\"A\""));
            assertTrue(changed.contains(
                    "\"section\":\"tagProcessing\""));
            assertTrue(changed.contains(
                    "\"quietTimeoutMillis\":300"));
            assertTrue(changed.contains(
                    "\"sweepCadenceMillis\":75"));
            assertTrue(changed.contains(
                    "\"overridden\":true"));

            ConfigurationControl.Update noChange =
                    fixture.handler
                            .configuration()
                            .setTagProcessing(
                                    new NodeId("A"),
                                    new ConfigurationControl
                                            .TagProcessingPatch(
                                                    300L,
                                                    null,
                                                    null,
                                                    null,
                                                    null));
            assertTrue(
                    noChange.result()
                            == ConfigurationControl
                                    .UpdateResult.NO_CHANGE);
            assertNull(client.pollMessage(250));

            ConfigurationControl.Update restartRequired =
                    fixture.handler
                            .configuration()
                            .setTagProcessing(
                                    new NodeId("A"),
                                    new ConfigurationControl
                                            .TagProcessingPatch(
                                                    null,
                                                    null,
                                                    null,
                                                    null,
                                                    128L));
            assertTrue(
                    restartRequired.result()
                            == ConfigurationControl
                                    .UpdateResult.RESTART_REQUIRED);
            assertNull(client.pollMessage(250));

            ConfigurationControl.Update cleared =
                    fixture.handler
                            .configuration()
                            .clearTagProcessing(
                                    new NodeId("A"));
            assertTrue(
                    cleared.result()
                            == ConfigurationControl
                                    .UpdateResult.APPLIED);

            String restored = client.awaitMessage();
            assertNotNull(restored);
            assertTrue(restored.contains(
                    "\"eventType\":\"CONFIGURATION_CHANGED\""));
            assertTrue(restored.contains(
                    "\"quietTimeoutMillis\":250"));
            assertTrue(restored.contains(
                    "\"sweepCadenceMillis\":50"));
            assertTrue(restored.contains(
                    "\"overridden\":false"));
        } finally {
            client.closeBlocking();
            server.close();
            fixture.close();
        }
    }

    @Test
    public void snapshotContainsEveryComposedTimingNode()
            throws Exception {
        TimingNodeFixture.Components first =
                TimingNodeFixture.createComponents(
                        new NodeId("A"),
                        new MemoryStore(),
                        () -> RECORDED_AT.instant());
        TimingNodeFixture.Components second =
                TimingNodeFixture.createComponents(
                        new NodeId("B"),
                        new MemoryStore(),
                        () -> RECORDED_AT.instant());
        PresentationGateway gateway =
                new PresentationGateway(
                        identity(),
                        nodes(first, second),
                        PresentationGatewayFixture.configurationControl(
                                new NodeId("A"),
                                new NodeId("B")));

        LifecycleTestSupport.activate(first.node());
        LifecycleTestSupport.activate(second.node());
        gateway.timingNode(new NodeId("B"))
                .open(new LocationId(24));

        WebSocketEndpoint server =
                new WebSocketEndpoint(
                        "127.0.0.1",
                        0,
                        gateway,
                        EVENT_CLOCK);
        server.start();

        TestClient client = connect(server.boundPort());
        try {
            String snapshot =
                    client.awaitMessage();
            assertNotNull(snapshot);
            assertTrue(snapshot.contains(
                    "\"eventType\":\"STATUS_SNAPSHOT\""));
            assertTrue(snapshot.contains(
                    "{\"id\":\"A\",\"locId\":null,\"state\":\"CLOSED\"}"));
            assertTrue(snapshot.contains(
                    "{\"id\":\"B\",\"locId\":24,\"state\":\"OPEN\"}"));
        } finally {
            client.closeBlocking();
            server.close();
            LifecycleTestSupport.deactivate(second.node());
            LifecycleTestSupport.deactivate(first.node());
        }
    }

    @Test
    public void snapshotRemainsAvailableAfterTimingDataLoadFailure()
            throws Exception {
        TimingNodeFixture.Components node = TimingNodeFixture.createComponents(
                new NodeId("A"),
                new FailingLoadStore(),
                () -> RECORDED_AT.instant());
        PresentationGateway handler =
                new PresentationGateway(
                        identity(),
                        nodes(node),
                        PresentationGatewayFixture.configurationControl(
                                new NodeId("A")));
        LifecycleTestSupport.activate(node.node());
        WebSocketEndpoint server = new WebSocketEndpoint(
                "127.0.0.1",
                0,
                handler,
                EVENT_CLOCK);
        server.start();

        try {
            TestClient client = connect(server.boundPort());
            try {
                String snapshot = client.awaitMessage();
                assertNotNull(snapshot);
                assertTrue(snapshot.contains("\"eventType\":\"STATUS_SNAPSHOT\""));
                assertTrue(snapshot.contains("\"id\":\"A\""));
                assertTrue(snapshot.contains("\"locId\":null"));
                assertTrue(snapshot.contains("\"state\":\"CLOSED\""));
            } finally {
                client.closeBlocking();
            }
        } finally {
            server.close();
            LifecycleTestSupport.deactivate(node.node());
        }
    }

    private static TestClient connect(int port) throws Exception {
        TestClient client = new TestClient(
                new URI("ws://127.0.0.1:" + port + WebSocketEndpoint.EVENTS_PATH));
        assertTrue(client.connectBlocking(2, TimeUnit.SECONDS));
        return client;
    }

    private static TestClient connectUpstream(
            int port)
            throws Exception {
        TestClient client =
                new TestClient(
                        new URI(
                                "ws://127.0.0.1:"
                                        + port
                                        + WebSocketEndpoint.UPSTREAM_EVENTS_PATH));
        assertTrue(
                client.connectBlocking(
                        2,
                        TimeUnit.SECONDS));
        return client;
    }

    private static String upstreamJson(
            UpstreamData data) {
        try {
            return new String(
                    new DefaultUpstreamDataCodec(
                            new DefaultTimingDataCodec())
                            .encode(data),
                    StandardCharsets.UTF_8);
        } catch (UpstreamDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "Could not encode test UpstreamData",
                    ex);
        }
    }

    private static void assertSnapshot(
            String json,
            String timingNodeId,
            String functionalState,
            String locationJson) {
        assertNotNull(json);
        assertTrue(json.contains("\"eventType\":\"STATUS_SNAPSHOT\""));
        assertTrue(json.contains("\"occurredAt\":\"2026-10-01T12:00:02.000Z\""));
        assertTrue(json.contains("\"id\":\"" + timingNodeId + "\""));
        assertTrue(json.contains("\"locId\":" + locationJson));
        assertTrue(json.contains(
                "\"state\":\"" + functionalState + "\""));
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "test-version",
                "abc123def456",
                "feature/test",
                "local",
                false);
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

    private static final class Fixture implements AutoCloseable {
        private final TimingNodeFixture.Components node;
        private final PresentationGateway handler;

        private Fixture() {
            node = TimingNodeFixture.createComponents(
                    new NodeId("A"),
                    new MemoryStore(),
                    () -> RECORDED_AT.instant());
            handler =
                    new PresentationGateway(
                            identity(),
                            nodes(node),
                            PresentationGatewayFixture.configurationControl(
                                    new NodeId("A")));
        }

        private void start() throws Exception {
            LifecycleTestSupport.activate(node.node());
        }

        @Override
        public void close() {
            LifecycleTestSupport.deactivate(node.node());
        }
    }

    private static final class FailingLoadStore
            implements TimingDataPersistence {
        @Override
        public LoadResult load() throws PersistenceException {
            throw new PersistenceException("expected persisted-data load failure");
        }

        @Override
        public void append(TimingRecord record) {
            throw new AssertionError("ERROR TimingNode must not append TimingData");
        }
    }

    private static final class MemoryStore implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(Collections.<TimingRecord>emptyList(), false);
        }

        @Override
        public void append(TimingRecord record) {
        }
    }

    private static final class TestClient extends WebSocketClient {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();

        private TestClient(URI uri) {
            super(uri);
        }

        private String awaitMessage() throws InterruptedException {
            return messages.poll(2, TimeUnit.SECONDS);
        }

        private String pollMessage(long timeoutMillis) throws InterruptedException {
            return messages.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
        }

        @Override
        public void onMessage(String message) {
            messages.offer(message);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
        }

        @Override
        public void onError(Exception ex) {
        }
    }
}
