package oss.relay.timing.timingpoint.domain.system.upstream.node;

import oss.relay.timing.upstreamdata.UpstreamData;
import oss.relay.timing.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns normal Central-to-node packet delivery state for one NodeUpstream.
 *
 * <p>Only one normal DN_PACKET can be processed at a time. A duplicate packet is not
 * applied again; the last accepted packet number is reported again through RECV. A gap is
 * likewise not processed and reports the last accepted packet number, including zero when
 * the node needs the Central side to rebuild current downstream state.</p>
 */
final class DownstreamDelivery {
    private final BooleanSupplier available;
    private final Consumer<UpstreamData.Message> sender;

    private long lastProcessedPacketNr;
    private UpstreamData lastProcessedPacket;
    private long processingPacketNr;

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
    synchronized boolean begin(UpstreamData packet) {
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(
                packet.direction() == UpstreamData.Direction.DN_PACKET,
                "downstream delivery requires DN_PACKET");
        failIfInvalidArgument(
                packet.packetNr() > 0L,
                "downstream delivery requires a positive packetNr");
        failIfInvalidArgument(
                packet.family() != UpstreamData.Family.PING,
                "PING does not use downstream delivery");
        failIfIllegalState(
                processingPacketNr == 0L,
                "DN_PACKET %s is already being processed",
                processingPacketNr);

        long receivedPacketNr = packet.packetNr();
        if (receivedPacketNr < lastProcessedPacketNr) {
            sendReceivePosition();
            return false;
        }

        if (receivedPacketNr == lastProcessedPacketNr) {
            failIfInvalidArgument(
                    samePacket(lastProcessedPacket, packet),
                    "DN_PACKET packetNr reused with different semantic content");
            sendReceivePosition();
            return false;
        }

        if (lastProcessedPacketNr == Long.MAX_VALUE
                || receivedPacketNr != lastProcessedPacketNr + 1L) {
            sendReceivePosition();
            return false;
        }

        processingPacketNr = receivedPacketNr;
        return true;
    }

    /** Commits one successfully processed downstream packet and emits RECV when possible. */
    synchronized void complete(UpstreamData packet) {
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfIllegalState(
                processingPacketNr == packet.packetNr(),
                "DN_PACKET completion does not match pending packet");

        lastProcessedPacketNr = packet.packetNr();
        lastProcessedPacket = packet;
        processingPacketNr = 0L;
        sendReceivePosition();
    }

    /** Drops one failed/incomplete packet without advancing the accepted position. */
    synchronized void cancelPending() {
        processingPacketNr = 0L;
    }

    /** Returns the last successfully processed normal downstream packet number. */
    synchronized long lastProcessedPacketNr() {
        return lastProcessedPacketNr;
    }

    /**
     * Announces the current downstream receive position for a new transport session.
     *
     * <p>Position 0 means this runtime has not processed a normal DN_PACKET and therefore
     * requests a full current-state resend. A positive position lets a restarted Central
     * continue packet numbering without resetting TimingNode-owned protocol state.</p>
     */
    synchronized void announceReceivePosition() {
        sendReceivePosition();
    }

    /** Compares the retained duplicate boundary without keeping a packet history. */
    private static boolean samePacket(
            UpstreamData first,
            UpstreamData second) {
        if (first == second) {
            return true;
        }
        if (first == null
                || second == null
                || first.direction() != second.direction()
                || !first.timingNodeId().equals(second.timingNodeId())
                || first.packetNr() != second.packetNr()
                || first.family() != second.family()) {
            return false;
        }

        switch (first.family()) {
            case TEAM_ASSIGNMENTS:
                return ((UpstreamData.TeamAssignments) first.message())
                        .changes()
                        .equals(
                                ((UpstreamData.TeamAssignments) second.message())
                                        .changes());

            case STAGE_STARTS:
                return ((UpstreamData.StageStarts) first.message())
                        .changes()
                        .equals(
                                ((UpstreamData.StageStarts) second.message())
                                        .changes());

            case TIMING_DATA_REQ:
                return ((UpstreamData.TimingDataRequest) first.message())
                                .fromSequence()
                        == ((UpstreamData.TimingDataRequest) second.message())
                                .fromSequence();

            case SEQ:
                UpstreamData.Sequence firstSequence =
                        (UpstreamData.Sequence) first.message();
                UpstreamData.Sequence secondSequence =
                        (UpstreamData.Sequence) second.message();
                return firstSequence.operation()
                                == secondSequence.operation()
                        && Objects.equals(
                                firstSequence.sequence(),
                                secondSequence.sequence());

            default:
                return false;
        }
    }

    private void sendReceivePosition() {
        if (!available.getAsBoolean()) {
            return;
        }
        sender.accept(
                DefaultUpstreamData.createReceive(
                        lastProcessedPacketNr));
    }
}
