package oss.relay.timing.timingpoint.domain.system.upstream.node;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownstreamDeliveryTest {

    @Test
    public void confirmsProcessedPacketAndRepeatsReceiveForDuplicate() {
        TestUpstreamStream stream = new TestUpstreamStream();
        DownstreamDelivery delivery =
                new DownstreamDelivery(
                        stream::isAvailable,
                        stream::send);

        UpstreamData first = sequencePacket(1L);
        assertTrue(delivery.begin(first));
        delivery.complete(first);

        assertReceive(stream.takeSent(), 1L);
        assertEquals(1L, delivery.lastProcessedPacketNr());

        assertFalse(delivery.begin(first));
        assertReceive(stream.takeSent(), 1L);
    }

    @Test
    public void duplicatePacketNrWithChangedContentIsRejected() {
        TestUpstreamStream stream = new TestUpstreamStream();
        DownstreamDelivery delivery =
                new DownstreamDelivery(
                        stream::isAvailable,
                        stream::send);

        UpstreamData first = sequencePacket(1L);
        assertTrue(delivery.begin(first));
        delivery.complete(first);
        stream.takeSent();

        UpstreamData changed =
                DefaultUpstreamData.createDnPacket(
                        new NodeId("A"),
                        1L,
                        DefaultUpstreamData.createSequenceAnswer(42L));

        try {
            delivery.begin(changed);
            org.junit.Assert.fail("expected changed duplicate rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("different semantic content"));
        }
    }

    @Test
    public void gapReportsLastProcessedPacketWithoutAdvancing() {
        TestUpstreamStream stream = new TestUpstreamStream();
        DownstreamDelivery delivery =
                new DownstreamDelivery(
                        stream::isAvailable,
                        stream::send);

        assertFalse(delivery.begin(sequencePacket(2L)));
        assertReceive(stream.takeSent(), 0L);
        assertEquals(0L, delivery.lastProcessedPacketNr());
    }

    @Test
    public void freshRuntimeCanRequestFullDownstreamState() {
        TestUpstreamStream stream = new TestUpstreamStream();
        DownstreamDelivery delivery =
                new DownstreamDelivery(
                        stream::isAvailable,
                        stream::send);

        delivery.requestFullStateIfUninitialized();
        assertReceive(stream.takeSent(), 0L);

        assertTrue(delivery.begin(sequencePacket(1L)));
        delivery.complete(sequencePacket(1L));
        stream.takeSent();

        delivery.requestFullStateIfUninitialized();
        assertEquals(0, stream.sentCount());
    }

    @Test
    public void successfulApplyAdvancesEvenWhenTransportDisappeared() {
        TestUpstreamStream stream = new TestUpstreamStream();
        DownstreamDelivery delivery =
                new DownstreamDelivery(
                        stream::isAvailable,
                        stream::send);

        UpstreamData first = sequencePacket(1L);
        assertTrue(delivery.begin(first));
        stream.setAvailable(false);
        delivery.complete(first);

        assertEquals(1L, delivery.lastProcessedPacketNr());
        assertEquals(0, stream.sentCount());

        stream.setAvailable(true);
        assertFalse(delivery.begin(sequencePacket(1L)));
        assertReceive(stream.takeSent(), 1L);
    }

    private static UpstreamData sequencePacket(long packetNr) {
        return DefaultUpstreamData.createDnPacket(
                new NodeId("A"),
                packetNr,
                DefaultUpstreamData.createSequenceRequest());
    }

    private static void assertReceive(
            UpstreamData.Message message,
            long expectedPacketNr) {
        UpstreamData.Receive receive =
                (UpstreamData.Receive) message;
        assertEquals(
                expectedPacketNr,
                receive.receivedPacketNr());
    }
}
