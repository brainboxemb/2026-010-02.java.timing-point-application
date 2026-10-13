package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.time.Instant;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpstreamMessagePortTest {

    @Test
    public void heartbeatTickIsNodeScopedAndTracksMissedIntervals() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            UpstreamData.Ping first =
                    port.heartbeatTick();

            assertEquals(
                    new NodeId("A"),
                    first.timingNodeId());
            assertSame(
                    UpstreamData.PingOperation.REQ,
                    first.operation());
            assertSame(
                    UpstreamData.PingMode.HB,
                    first.mode());
            assertEquals("hb-1", first.pingId());

            UpstreamMessagePort.HeartbeatStatus firstStatus =
                    port.heartbeatStatus();
            assertEquals(1L, firstStatus.requestsSent());
            assertEquals(0L, firstStatus.answersReceived());
            assertEquals(0L, firstStatus.missedIntervals());
            assertTrue(firstStatus.answerPending());

            UpstreamData.Ping second =
                    port.heartbeatTick();
            assertEquals("hb-2", second.pingId());

            UpstreamMessagePort.HeartbeatStatus secondStatus =
                    port.heartbeatStatus();
            assertEquals(2L, secondStatus.requestsSent());
            assertEquals(1L, secondStatus.missedIntervals());
            assertTrue(secondStatus.answerPending());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void matchingHeartbeatAnswerClearsPendingState() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();
            UpstreamData.Ping request =
                    port.heartbeatTick();

            UpstreamData.Ping response =
                    port.handlePing(
                            DefaultUpstreamData.ping(
                                    new NodeId("A"),
                                    UpstreamData.PingOperation.ANS,
                                    UpstreamData.PingMode.HB,
                                    request.pingId()));

            assertNull(response);

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(1L, status.answersReceived());
            assertEquals(0L, status.missedIntervals());
            assertFalse(status.answerPending());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void staleHeartbeatAnswerDoesNotAcknowledgeCurrentRequest() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();
            port.heartbeatTick();
            port.heartbeatTick();

            port.handlePing(
                    DefaultUpstreamData.ping(
                            new NodeId("A"),
                            UpstreamData.PingOperation.ANS,
                            UpstreamData.PingMode.HB,
                            "hb-1"));

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(0L, status.answersReceived());
            assertEquals(1L, status.missedIntervals());
            assertTrue(status.answerPending());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void inboundPingRequestProducesAnswerWithoutAnotherRequest() {
        TimingNode node = node("A");
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            UpstreamData.Ping answer =
                    port.handlePing(
                            DefaultUpstreamData.ping(
                                    new NodeId("A"),
                                    UpstreamData.PingOperation.REQ,
                                    UpstreamData.PingMode.HB,
                                    "peer-42"));

            assertEquals(
                    new NodeId("A"),
                    answer.timingNodeId());
            assertSame(
                    UpstreamData.PingOperation.ANS,
                    answer.operation());
            assertSame(
                    UpstreamData.PingMode.HB,
                    answer.mode());
            assertEquals("peer-42", answer.pingId());

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(0L, status.requestsSent());
            assertEquals(0L, status.answersReceived());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void pingForDifferentNodeIsRejected() {
        TimingNode node = node("A");
        node.activate();
        try {
            try {
                node.upstreamMessagePort()
                        .handlePing(
                                DefaultUpstreamData.ping(
                                        new NodeId("B"),
                                        UpstreamData.PingOperation.REQ,
                                        null,
                                        "manual-1"));
                fail("expected node mismatch");
            } catch (IllegalArgumentException expected) {
                assertTrue(
                        expected.getMessage()
                                .contains("NodeId"));
            }
        } finally {
            node.deactivate();
        }
    }

    private static TimingNode node(String nodeId) {
        return new TimingNode(
                new NodeId(nodeId),
                new EmptyPersistence(),
                new DefaultTimingDataFactory(),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                EventData.empty());
    }

    private static final class EmptyPersistence
            implements TimingDataPersistence {
        @Override
        public LoadResult load() {
            return new LoadResult(
                    Collections.<TimingData>emptyList(),
                    false);
        }

        @Override
        public void append(TimingData data) {
            // Upstream heartbeat tests do not commit TimingData.
        }
    }
}
