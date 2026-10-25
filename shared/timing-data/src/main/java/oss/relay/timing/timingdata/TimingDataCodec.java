package oss.relay.timing.timingdata;

/**
 * Translates one scalar TimingRecord payload to or from a concrete IF-05 representation.
 *
 * <p>Record framing belongs to the caller. A codec handles exactly one record;
 * JSON Lines framing, incomplete-tail handling and storage recovery belong to
 * the persistence layer.</p>
 */
public interface TimingDataCodec {

    byte[] encode(TimingRecord record) throws CodecException;

    TimingRecord decode(byte[] encodedRecord) throws CodecException;

    /** Checked codec failure carrying readable envelope context when available. */
    final class CodecException extends Exception {

        public enum Reason {
            INVALID_DATA,
            UNSUPPORTED_VERSION,
            UNSUPPORTED_RECORD_TYPE,
            ENCODE_FAILURE
        }

        private final Reason reason;
        private final Integer version;
        private final TimingRecord.RecordKey key;
        private final TimingDataTypes.LocationId locationId;
        private final String recordType;
        private final TimingTimestamp effectiveTime;
        private final TimingTimestamp recordedAt;

        public CodecException(Reason reason, String message) {
            this(reason, message, null, null, null, null, null, null, null);
        }

        public CodecException(Reason reason, String message, Throwable cause) {
            this(reason, message, cause, null, null, null, null, null, null);
        }

        private CodecException(
                Reason reason,
                String message,
                Throwable cause,
                Integer version,
                TimingRecord.RecordKey key,
                TimingDataTypes.LocationId locationId,
                String recordType,
                TimingTimestamp effectiveTime,
                TimingTimestamp recordedAt) {
            super(message, cause);
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
            this.reason = reason;
            this.version = version;
            this.key = key;
            this.locationId = locationId;
            this.recordType = recordType;
            this.effectiveTime = effectiveTime;
            this.recordedAt = recordedAt;
        }

        public static CodecException unsupportedVersion(int version, String message) {
            return new CodecException(
                    Reason.UNSUPPORTED_VERSION,
                    message,
                    null,
                    version,
                    null,
                    null,
                    null,
                    null,
                    null);
        }

        public static CodecException unsupportedRecordType(
                int version,
                TimingRecord.RecordKey key,
                TimingDataTypes.LocationId locationId,
                String recordType,
                TimingTimestamp effectiveTime,
                TimingTimestamp recordedAt,
                String message) {
            if (key == null) {
                throw new IllegalArgumentException("key must not be null");
            }
            if (locationId == null) {
                throw new IllegalArgumentException("locationId must not be null");
            }
            if (recordType == null || recordType.trim().isEmpty()) {
                throw new IllegalArgumentException("recordType must not be blank");
            }
            if (effectiveTime == null) {
                throw new IllegalArgumentException("effectiveTime must not be null");
            }
            return new CodecException(
                    Reason.UNSUPPORTED_RECORD_TYPE,
                    message,
                    null,
                    version,
                    key,
                    locationId,
                    recordType,
                    effectiveTime,
                    recordedAt);
        }

        public Reason reason() {
            return reason;
        }

        public Integer version() {
            return version;
        }

        public TimingRecord.RecordKey key() {
            return key;
        }

        public TimingDataTypes.LocationId locationId() {
            return locationId;
        }

        public String recordType() {
            return recordType;
        }

        public TimingTimestamp effectiveTime() {
            return effectiveTime;
        }

        public TimingTimestamp recordedAt() {
            return recordedAt;
        }
    }
}
