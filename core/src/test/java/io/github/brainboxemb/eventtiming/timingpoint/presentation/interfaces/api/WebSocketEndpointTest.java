package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeProxy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.PresentationGatewayFixture;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebSocketEndpointTest {
    private static final TimingTimestamp TIME =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED_AT =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");
    private static final Clock EVENT_CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T12:00:02Z"), ZoneOffset.UTC);

    @Test
    public void sendsCompleteSnapshotOnConnectAndReconnectWithoutHistoryReplay()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();

        // Commit history before the WebSocket endpoint/client exists.
        fixture.handler.timingNode().open(new LocationId(24));
        fixture.handler.timingNode().applyAutomaticRegistration(
                TimingNodeProxy.AutomaticRegistrationAction.ADD,
                new RegistrationId("N0000"),
                TIME);

        WebSocketEndpoint server = new WebSocketEndpoint(
                "127.0.0.1",
                0,
                fixture.handler,
                EVENT_CLOCK);
        wireApplicationEvents(
                server,
                fixture.handler);
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
    public void broadcastsStatusChangesAndCommittedTimingDataAutomatically()
            throws Exception {
        Fixture fixture = new Fixture();
        fixture.start();
        WebSocketEndpoint server = new WebSocketEndpoint(
                "127.0.0.1",
                0,
                fixture.handler,
                EVENT_CLOCK);
        wireApplicationEvents(
                server,
                fixture.handler);
        server.start();
        TestClient client = connect(server.boundPort());

        try {
            assertSnapshot(
                    client.awaitMessage(),
                    "A",
                    "CLOSED",
                    "null");

            fixture.handler.timingNode().open(new LocationId(24));
            String opened = client.awaitMessage();
            assertTrue(opened.contains("\"eventType\":\"STATUS_CHANGED\""));
            assertTrue(opened.contains("\"locationId\":24"));
            assertTrue(opened.contains("\"state\":\"OPEN\""));

            fixture.handler.timingNode().applyAutomaticRegistration(
                    TimingNodeProxy.AutomaticRegistrationAction.ADD,
                    new RegistrationId("N0001"),
                    TIME);
            String committed = client.awaitMessage();
            assertTrue(committed.contains(
                    "\"eventType\":\"TIMING_DATA_COMMITTED\""));
            assertTrue(committed.contains("\"seqNr\":1"));
            assertTrue(committed.contains("\"locId\":24"));
            assertTrue(committed.contains("\"recType\":\"AUTO_REG\""));
            assertTrue(committed.contains(
                    "\"regId\":\"N0001\""));
            assertTrue(committed.contains("\"code\":[\"ADD\"]"));
            assertTrue(committed.contains(
                    "\"occurredAt\":\"2026-10-01T12:00:02Z\""));
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
        wireApplicationEvents(
                server,
                fixture.handler);
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
                    "\"occurredAt\":\"2026-10-01T12:00:02Z\""));
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
    public void snapshotExposesContainedTimingDataRecoveryFailure()
            throws Exception {
        TimingNode node = TimingNodeFixture.create(
                new NodeId("A"),
                new FailingRecoveryStore(),
                () -> RECORDED_AT.instant());
        PresentationGateway handler =
                new PresentationGateway(
                        identity(),
                        node,
                        PresentationGatewayFixture.configurationControl(
                                new NodeId("A")));
        node.activate();
        WebSocketEndpoint server = new WebSocketEndpoint(
                "127.0.0.1",
                0,
                handler,
                EVENT_CLOCK);
        wireApplicationEvents(
                server,
                handler);
        server.start();

        try {
            TestClient client = connect(server.boundPort());
            try {
                String snapshot = client.awaitMessage();
                assertNotNull(snapshot);
                assertTrue(snapshot.contains("\"eventType\":\"STATUS_SNAPSHOT\""));
                assertTrue(snapshot.contains("\"id\":\"A\""));
                assertTrue(snapshot.contains("\"locationId\":null"));
                assertTrue(snapshot.contains("\"state\":\"ERROR\""));
                assertTrue(snapshot.contains(
                        "\"code\":\"TIMING_DATA_RECOVERY_FAILED\""));
                assertTrue(snapshot.contains("\"severity\":\"ERROR\""));
                assertTrue(snapshot.contains("\"nodeId\":\"A\""));
                assertTrue(snapshot.contains("expected recovery failure"));
            } finally {
                client.closeBlocking();
            }
        } finally {
            server.close();
            node.deactivate();
        }
    }

    private static void wireApplicationEvents(
            WebSocketEndpoint endpoint,
            PresentationGateway gateway) {
        gateway.timingNode()
                .statusChangedEvent()
                .subscribe(
                        endpoint::onTimingNodeStatusChanged);
        gateway.timingNode()
                .timingDataCommittedEvent()
                .subscribe(
                        endpoint::onTimingDataCommitted);
        gateway.configuration()
                .changes()
                .subscribe(
                        endpoint::onConfigurationChanged);
    }

    private static TestClient connect(int port) throws Exception {
        TestClient client = new TestClient(
                new URI("ws://127.0.0.1:" + port + WebSocketEndpoint.EVENTS_PATH));
        assertTrue(client.connectBlocking(2, TimeUnit.SECONDS));
        return client;
    }

    private static void assertSnapshot(
            String json,
            String timingNodeId,
            String state,
            String locationJson) {
        assertNotNull(json);
        assertTrue(json.contains("\"eventType\":\"STATUS_SNAPSHOT\""));
        assertTrue(json.contains("\"occurredAt\":\"2026-10-01T12:00:02Z\""));
        assertTrue(json.contains("\"id\":\"" + timingNodeId + "\""));
        assertTrue(json.contains("\"locationId\":" + locationJson));
        assertTrue(json.contains("\"state\":\"" + state + "\""));
        assertTrue(json.contains("\"problems\":[]"));
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

    private static final class Fixture implements AutoCloseable {
        private final TimingNode node;
        private final PresentationGateway handler;

        private Fixture() {
            node = TimingNodeFixture.create(
                    new NodeId("A"),
                    new MemoryStore(),
                    () -> RECORDED_AT.instant());
            handler =
                    new PresentationGateway(
                            identity(),
                            node,
                            PresentationGatewayFixture.configurationControl(
                                    new NodeId("A")));
        }

        private void start() {
            node.activate();
        }

        @Override
        public void close() {
            node.deactivate();
        }
    }

    private static final class FailingRecoveryStore
            implements TimingDataPersistence {
        @Override
        public LoadResult load() throws PersistenceException {
            throw new PersistenceException("expected recovery failure");
        }

        @Override
        public void append(TimingData data) {
            throw new AssertionError("ERROR TimingNode must not append TimingData");
        }
    }

    private static final class MemoryStore implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(Collections.<TimingData>emptyList(), false);
        }

        @Override
        public void append(TimingData data) {
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
