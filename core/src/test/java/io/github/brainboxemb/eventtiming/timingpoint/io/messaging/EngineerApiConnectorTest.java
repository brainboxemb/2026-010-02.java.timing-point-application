package io.github.brainboxemb.eventtiming.timingpoint.io.messaging;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.testsupport.TimingNodeFixture;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.activate;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleTestSupport.deactivate;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class EngineerApiConnectorTest {
    private final ScheduledThreadPoolExecutor scheduler =
            new ScheduledThreadPoolExecutor(1);

    @After
    public void closeScheduler() {
        scheduler.shutdownNow();
    }

    @Test
    public void heartbeatRunsOnlyWhileConnectorIsConnected()
            throws Exception {
        EngineerApiConnector connector =
                new EngineerApiConnector(
                        scheduler,
                        8,
                        8,
                        Duration.ofMillis(20L));
        CountDownLatch firstConnectionTicks =
                new CountDownLatch(2);
        AtomicInteger ticks =
                new AtomicInteger();

        connector.bindNode(
                new NodeId("A"),
                () -> true,
                () -> {
                },
                () -> {
                    ticks.incrementAndGet();
                    firstConnectionTicks.countDown();
                });

        EngineerApiConnector.Output firstPeer =
                message -> true;
        assertTrue(connector.connect(firstPeer));
        assertTrue(
                firstConnectionTicks.await(
                        1L,
                        TimeUnit.SECONDS));

        connector.disconnect(firstPeer);
        int afterDisconnect =
                ticks.get();
        Thread.sleep(80L);
        assertEquals(
                afterDisconnect,
                ticks.get());

        EngineerApiConnector.Output secondPeer =
                message -> true;
        assertTrue(connector.connect(secondPeer));

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(1L);
        while (ticks.get() == afterDisconnect
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(
                ticks.get() > afterDisconnect);

        connector.disconnect(secondPeer);
    }

    @Test
    public void keepsIndependentNodeInputsAndOneSharedOutputOrder() {
        EngineerApiConnector connector =
                new EngineerApiConnector(scheduler);
        AtomicInteger wakeA = new AtomicInteger();
        AtomicInteger wakeB = new AtomicInteger();
        AtomicInteger connected = new AtomicInteger();

        connector.bindNode(
                new NodeId("A"),
                () -> {
                    wakeA.incrementAndGet();
                    return true;
                },
                connected::incrementAndGet,
                () -> {
                });
        connector.bindNode(
                new NodeId("B"),
                () -> {
                    wakeB.incrementAndGet();
                    return true;
                },
                connected::incrementAndGet,
                () -> {
                });

        List<UpstreamData> output =
                new ArrayList<UpstreamData>();
        EngineerApiConnector.Output peer =
                message -> output.add(message);
        assertTrue(connector.connect(peer));
        assertEquals(2, connected.get());

        UpstreamData.Sync inputA =
                DefaultUpstreamData.sync(
                        new NodeId("A"),
                        UpstreamData.Stream.RACE_DATA,
                        1L);
        UpstreamData.Sync inputB =
                DefaultUpstreamData.sync(
                        new NodeId("B"),
                        UpstreamData.Stream.RACE_DATA,
                        2L);

        assertSame(
                EngineerApiConnector.ReceiveResult.ACCEPTED,
                connector.receive(inputA));
        assertSame(
                EngineerApiConnector.ReceiveResult.ACCEPTED,
                connector.receive(inputB));
        assertEquals(1, wakeA.get());
        assertEquals(1, wakeB.get());

        assertTrue(connector.hasMessage(new NodeId("A")));
        assertTrue(connector.hasMessage(new NodeId("B")));
        assertSame(inputA, connector.read(new NodeId("A")));
        assertSame(inputB, connector.read(new NodeId("B")));
        assertFalse(connector.hasMessage(new NodeId("A")));
        assertFalse(connector.hasMessage(new NodeId("B")));

        UpstreamData.Ping first =
                DefaultUpstreamData.ping(
                        new NodeId("B"),
                        UpstreamData.PingOperation.REQ,
                        null,
                        "one");
        UpstreamData.Ping second =
                DefaultUpstreamData.ping(
                        new NodeId("A"),
                        UpstreamData.PingOperation.REQ,
                        null,
                        "two");
        connector.send(first);
        connector.send(second);

        assertEquals(2, output.size());
        assertSame(first, output.get(0));
        assertSame(second, output.get(1));
    }

    @Test
    public void raceDataTravelsThroughNodeServiceBeforeAckReturns()
            throws Exception {
        NodeId nodeId =
                new NodeId("A");
        EngineerApiConnector connector =
                new EngineerApiConnector(scheduler);
        TimingNodeFixture.Components components =
                TimingNodeFixture.createDirectComponents(
                        nodeId,
                        new EmptyPersistence(),
                        () -> Instant.parse(
                                "2026-10-20T12:00:00Z"),
                        EventData.empty(),
                        UpstreamStream.forNode(
                                connector,
                                nodeId));

        connector.bindNode(
                nodeId,
                components.nodeUpstream()::inputAvailable,
                () -> {
                    // This test exercises one inbound message only.
                },
                () -> {
                });

        LinkedBlockingQueue<UpstreamData> outbound =
                new LinkedBlockingQueue<UpstreamData>();
        EngineerApiConnector.Output peer =
                message -> outbound.offer(message);

        activate(components.node());
        activate(components.nodeUpstream());
        try {
            assertTrue(
                    connector.connect(peer));

            UpstreamData.RaceData raceData =
                    DefaultUpstreamData.raceData(
                            nodeId,
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "R0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList());

            assertSame(
                    EngineerApiConnector.ReceiveResult.ACCEPTED,
                    connector.receive(raceData));

            UpstreamData message =
                    outbound.poll(
                            1L,
                            TimeUnit.SECONDS);
            assertTrue(
                    message instanceof UpstreamData.Ack);

            UpstreamData.Ack ack =
                    (UpstreamData.Ack) message;
            assertEquals(1L, ack.sequence());
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    ack.stream());

            assertEquals(
                    new TeamId("0042"),
                    components.raceData().teamIdFor(
                            new RegistrationId("R0001")));
        } finally {
            connector.disconnect(peer);
            deactivate(components.nodeUpstream());
            deactivate(components.node());
        }
    }

    @Test
    public void requiresOneConnectedPeerForInboundAndOutbound() {
        EngineerApiConnector connector =
                new EngineerApiConnector(scheduler);
        connector.bindNode(
                new NodeId("A"),
                () -> true,
                () -> {
                },
                () -> {
                });

        UpstreamData.Sync input =
                DefaultUpstreamData.sync(
                        new NodeId("A"),
                        UpstreamData.Stream.RACE_DATA,
                        0L);

        assertSame(
                EngineerApiConnector.ReceiveResult.NOT_CONNECTED,
                connector.receive(input));

        EngineerApiConnector.Output first =
                message -> true;
        EngineerApiConnector.Output second =
                message -> true;

        assertTrue(connector.connect(first));
        assertFalse(connector.connect(second));
        connector.disconnect(first);
        assertFalse(connector.isAvailable());
    }

    @Test
    public void removesMessageWhenNodeCannotRetainWakeRequest() {
        EngineerApiConnector connector =
                new EngineerApiConnector(scheduler);
        connector.bindNode(
                new NodeId("A"),
                () -> false,
                () -> {
                },
                () -> {
                });
        EngineerApiConnector.Output peer =
                message -> true;
        assertTrue(connector.connect(peer));

        assertSame(
                EngineerApiConnector.ReceiveResult.INPUT_REJECTED,
                connector.receive(
                        DefaultUpstreamData.sync(
                                new NodeId("A"),
                                UpstreamData.Stream.RACE_DATA,
                                0L)));
        assertFalse(connector.hasMessage(new NodeId("A")));
    }
    private static final class EmptyPersistence
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingRecord>emptyList(),
                    false);
        }

        @Override
        public void append(
                TimingRecord record) {
            // This upstream test does not commit TimingData.
        }
    }

}
