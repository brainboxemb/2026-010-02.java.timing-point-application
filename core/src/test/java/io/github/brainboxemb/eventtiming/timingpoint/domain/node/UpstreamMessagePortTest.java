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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class UpstreamMessagePortTest {

    @Test
    public void raceDataIsReadFromStreamAndAckIsSentToStream() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNode node =
                node("A", stream);
        node.activate();
        try {
            stream.receive(
                    DefaultUpstreamData.raceData(
                            new NodeId("A"),
                            1L,
                            Collections.singletonList(
                                    DefaultUpstreamData.teamAssignmentSet(
                                            "RT-R-0001",
                                            "0042")),
                            Collections.<UpstreamData.StageStartTimeChange>emptyList()));

            assertTrue(
                    node.upstreamMessagePort()
                            .inputAvailable());

            UpstreamData.Ack ack =
                    (UpstreamData.Ack) stream.takeSent();
            assertSame(
                    UpstreamData.Stream.RACE_DATA,
                    ack.stream());
            assertEquals(1L, ack.sequence());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void heartbeatUsesSameOutboundStreamAndTracksMissedIntervals() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNode node =
                node("A", stream);
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();

            port.heartbeatTick();
            UpstreamData.Ping first =
                    (UpstreamData.Ping) stream.takeSent();

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

            port.heartbeatTick();
            UpstreamData.Ping second =
                    (UpstreamData.Ping) stream.takeSent();
            assertEquals("hb-2", second.pingId());

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(2L, status.requestsSent());
            assertEquals(0L, status.answersReceived());
            assertEquals(1L, status.missedIntervals());
            assertTrue(status.answerPending());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void matchingHeartbeatAnswerIsReadFromStream() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNode node =
                node("A", stream);
        node.activate();
        try {
            UpstreamMessagePort port =
                    node.upstreamMessagePort();
            port.heartbeatTick();
            UpstreamData.Ping request =
                    (UpstreamData.Ping) stream.takeSent();

            stream.receive(
                    DefaultUpstreamData.ping(
                            new NodeId("A"),
                            UpstreamData.PingOperation.ANS,
                            UpstreamData.PingMode.HB,
                            request.pingId()));
            assertTrue(port.inputAvailable());

            UpstreamMessagePort.HeartbeatStatus status =
                    port.heartbeatStatus();
            assertEquals(1L, status.answersReceived());
            assertEquals(0L, status.missedIntervals());
            assertFalse(status.answerPending());
            assertEquals(0, stream.sentCount());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void inboundPingRequestProducesAnswerOnSameStream() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        TimingNode node =
                node("A", stream);
        node.activate();
        try {
            stream.receive(
                    DefaultUpstreamData.ping(
                            new NodeId("A"),
                            UpstreamData.PingOperation.REQ,
                            UpstreamData.PingMode.HB,
                            "peer-42"));

            assertTrue(
                    node.upstreamMessagePort()
                            .inputAvailable());

            UpstreamData.Ping answer =
                    (UpstreamData.Ping) stream.takeSent();
            assertSame(
                    UpstreamData.PingOperation.ANS,
                    answer.operation());
            assertEquals("peer-42", answer.pingId());
        } finally {
            node.deactivate();
        }
    }

    @Test
    public void unavailableStreamDoesNotScheduleNodeWork() {
        InMemoryUpstreamStream stream =
                new InMemoryUpstreamStream();
        stream.setAvailable(false);
        stream.receive(
                DefaultUpstreamData.ping(
                        new NodeId("A"),
                        UpstreamData.PingOperation.REQ,
                        null,
                        "manual-1"));

        TimingNode node =
                node("A", stream);
        node.activate();
        try {
            assertFalse(
                    node.upstreamMessagePort()
                            .inputAvailable());
            assertEquals(0, stream.sentCount());
        } finally {
            node.deactivate();
        }
    }

    private static TimingNode node(
            String nodeId,
            UpstreamStream stream) {
        return new TimingNode(
                new NodeId(nodeId),
                new EmptyPersistence(),
                new DefaultTimingDataFactory(),
                () -> Instant.parse("2026-10-13T12:00:00Z"),
                EventData.empty(),
                stream);
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
            // Upstream-only tests do not commit TimingData.
        }
    }
}
