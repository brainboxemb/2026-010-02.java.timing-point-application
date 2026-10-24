package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns normal Central-to-node packet delivery state for one NodeUpstream.
 *
 * <p>Only one normal DN_PACKET can be processed at a time. A duplicate packet is not
 * applied again; the last accepted packet id is reported again through RECV. A gap is
 * likewise not processed and reports the last accepted packet id, including zero when
 * the node needs the Central side to rebuild current downstream state.</p>
 */
final class DownstreamDelivery {
    private final BooleanSupplier available;
    private final Consumer<UpstreamData.Message> sender;

    private long lastProcessedPacketId;
    private long processingPacketId;

    DownstreamDelivery(
            BooleanSupplier available,
            Consumer<UpstreamData.Message> sender) {
        failIfInvalidArgument(
                available != null,
                "available must not be null");
        failIfInvalidArgument(
                sender != null,
                "sender must not be null");
        this.available = available;
        this.sender = sender;
    }

    /**
     * Starts processing when the packet is exactly the next normal downstream packet.
     *
     * @return true only when the semantic message must be processed
     */
    boolean begin(UpstreamData packet) {
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(
                packet.direction() == UpstreamData.Direction.DN_PACKET,
                "downstream delivery requires DN_PACKET");
        failIfInvalidArgument(
                packet.packetId() > 0L,
                "downstream delivery requires a positive packetId");
        failIfInvalidArgument(
                packet.family() != UpstreamData.Family.PING,
                "PING does not use downstream delivery");
        failIfIllegalState(
                processingPacketId == 0L,
                "DN_PACKET %s is already being processed",
                processingPacketId);

        long receivedPacketId = packet.packetId();
        if (receivedPacketId <= lastProcessedPacketId) {
            sendReceivePosition();
            return false;
        }

        if (lastProcessedPacketId == Long.MAX_VALUE
                || receivedPacketId != lastProcessedPacketId + 1L) {
            sendReceivePosition();
            return false;
        }

        processingPacketId = receivedPacketId;
        return true;
    }

    /** Commits one successfully processed downstream packet and emits RECV when possible. */
    void complete(long packetId) {
        failIfIllegalState(
                processingPacketId == packetId,
                "DN_PACKET completion does not match pending packet");

        lastProcessedPacketId = packetId;
        processingPacketId = 0L;
        sendReceivePosition();
    }

    /** Drops one failed/incomplete packet without advancing the accepted position. */
    void cancelPending() {
        processingPacketId = 0L;
    }

    /** Returns the last successfully processed normal downstream packet id. */
    long lastProcessedPacketId() {
        return lastProcessedPacketId;
    }

    private void sendReceivePosition() {
        if (!available.getAsBoolean()) {
            return;
        }
        sender.accept(
                DefaultUpstreamData.createReceive(
                        lastProcessedPacketId));
    }
}
