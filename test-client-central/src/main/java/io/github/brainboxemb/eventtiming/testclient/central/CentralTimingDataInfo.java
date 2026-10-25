package io.github.brainboxemb.eventtiming.testclient.central;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/** Central-owned record-oriented projection of one accepted IF-05 TimingRecord. */
record CentralTimingDataInfo(
        String timingNodeId,
        long sequenceNumber,
        int locationId,
        String recordType,
        String registrationId,
        List<String> codes,
        String effectiveTime,
        String recordedAt,
        String rawJson) {

    private static final TimingDataCodec CODEC =
            new DefaultTimingDataCodec();

    static CentralTimingDataInfo from(TimingRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        return new CentralTimingDataInfo(
                String.valueOf(record.nodeId()),
                record.sequenceNumber(),
                record.locationId(),
                recordType(record),
                registrationId(record),
                codes(record),
                timestamp(record.effectiveTimeMillis()),
                record.hasRecordedAtMillis()
                        ? timestamp(record.recordedAtMillis())
                        : "",
                rawJson(record));
    }

    private static String registrationId(TimingRecord record) {
        return record.registrationId() == 0
                ? null
                : RegistrationId.fromPackedValue(
                        record.registrationId()).value();
    }

    private static String recordType(TimingRecord record) {
        return switch (record.kind()) {
            case AUTOMATIC_REGISTRATION -> "AUTO_REG";
            case MANUAL_REGISTRATION -> "MAN_REG";
            case NODE_OPEN, NODE_CLOSE -> "NODE_INFO";
        };
    }

    private static List<String> codes(TimingRecord record) {
        return switch (record.kind()) {
            case AUTOMATIC_REGISTRATION ->
                    List.of(record.action().name());
            case MANUAL_REGISTRATION ->
                    List.of(
                            record.action().name(),
                            record.manualTimeSource()
                                            == TimingRecord.ManualTimeSource.AUTOMATIC
                                    ? "AUTO"
                                    : "MAN");
            case NODE_OPEN -> List.of("OPEN");
            case NODE_CLOSE -> List.of("CLOSE");
        };
    }

    private static String timestamp(long epochMillis) {
        return new TimingTimestamp(
                Instant.ofEpochMilli(epochMillis))
                .toString();
    }

    private static String rawJson(TimingRecord record) {
        try {
            return new String(
                    CODEC.encode(record),
                    StandardCharsets.UTF_8);
        } catch (TimingDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "Could not encode Central TimingRecord",
                    ex);
        }
    }
}
