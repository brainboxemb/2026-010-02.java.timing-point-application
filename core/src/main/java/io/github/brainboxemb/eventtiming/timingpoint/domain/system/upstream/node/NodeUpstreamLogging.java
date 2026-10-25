package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.time.Instant;

/** Diagnostic logging for the node-scoped IF-06 semantic boundary. */
final class NodeUpstreamLogging {
    private NodeUpstreamLogging() {
    }

    /** Builds one compact summary at the semantic packet boundary. */
    static String summary(UpstreamData packet) {
        String prefix =
                "node=" + packet.timingNodeId().value()
                        + ", packetNr=" + packet.packetNr()
                        + ", type=" + packet.family().name();

        switch (packet.family()) {
            case TIMING_DATA:
                UpstreamData.TimingDataTransfer timingData =
                        (UpstreamData.TimingDataTransfer) packet.message();
                int recordCount = timingData.records().size();
                if (recordCount == 1) {
                    return prefix
                            + singleTimingDataSummary(
                                    timingData.records().get(0));
                }
                long firstSequence =
                        timingData.records().get(0).sequenceNumber();
                long lastSequence =
                        timingData.records().get(recordCount - 1).sequenceNumber();
                return prefix
                        + ", seq=" + firstSequence + ".." + lastSequence
                        + ", records=" + recordCount;

            case RACE_DATA:
                UpstreamData.RaceData raceData =
                        (UpstreamData.RaceData) packet.message();
                return prefix
                        + ", location=" + raceData.locationId().value()
                        + ", changes="
                        + (raceData.teamAssignmentChanges().size()
                                + raceData.stageStartTimeChanges().size());

            case TIMING_DATA_REQ:
                UpstreamData.TimingDataRequest request =
                        (UpstreamData.TimingDataRequest) packet.message();
                return prefix + ", fromSeq=" + request.fromSequence();

            case SEQ:
                UpstreamData.Sequence sequence =
                        (UpstreamData.Sequence) packet.message();
                return prefix
                        + ", op=" + sequence.operation().name()
                        + ", seq="
                        + (sequence.sequence() == null
                                ? "-"
                                : sequence.sequence().toString());

            case RECV:
                UpstreamData.Receive receive =
                        (UpstreamData.Receive) packet.message();
                return prefix
                        + ", receivedPacketNr="
                        + receive.receivedPacketNr();

            case PING:
                UpstreamData.Ping ping =
                        (UpstreamData.Ping) packet.message();
                return prefix
                        + ", op=" + ping.operation().name()
                        + ", mode="
                        + (ping.mode() == null ? "-" : ping.mode().name())
                        + ", pingId="
                        + (ping.pingId() == null ? "-" : ping.pingId());

            default:
                return prefix;
        }
    }

    /** Uses persisted IF-05 vocabulary when one TimingData record can be shown directly. */
    private static String singleTimingDataSummary(TimingRecord record) {
        StringBuilder summary =
                new StringBuilder()
                        .append(", seq=")
                        .append(record.sequenceNumber())
                        .append(", recType=")
                        .append(timingRecordType(record.kind()))
                        .append(", loc=")
                        .append(record.locationId())
                        .append(", time=")
                        .append(utcTime(record.effectiveTimeMillis()));

        if (record.registrationId() != 0) {
            summary.append(", regId=")
                    .append(
                            RegistrationId.fromPackedValue(
                                    record.registrationId()).value());
        }

        summary.append(", code=")
                .append(timingRecordCode(record));
        return summary.toString();
    }

    private static String utcTime(long epochMillis) {
        return new TimingTimestamp(
                Instant.ofEpochMilli(epochMillis))
                .toString();
    }

    private static String timingRecordType(TimingRecord.Kind kind) {
        switch (kind) {
            case AUTOMATIC_REGISTRATION:
                return "AUTO_REG";
            case MANUAL_REGISTRATION:
                return "MAN_REG";
            case NODE_OPEN:
            case NODE_CLOSE:
                return "NODE_INFO";
            default:
                throw new IllegalArgumentException(
                        "Unsupported TimingRecord kind " + kind);
        }
    }

    private static String timingRecordCode(TimingRecord record) {
        switch (record.kind()) {
            case AUTOMATIC_REGISTRATION:
                return record.action().name();
            case MANUAL_REGISTRATION:
                return record.action().name()
                        + ","
                        + (record.manualTimeSource()
                                        == TimingRecord.ManualTimeSource.AUTOMATIC
                                ? "AUTO"
                                : "MAN");
            case NODE_OPEN:
                return "OPEN";
            case NODE_CLOSE:
                return "CLOSE";
            default:
                throw new IllegalArgumentException(
                        "Unsupported TimingRecord kind " + record.kind());
        }
    }
}
