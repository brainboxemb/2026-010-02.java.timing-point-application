package io.github.brainboxemb.eventtiming.timingdata;

/**
 * Translates one TimingData record payload to or from a concrete representation.
 *
 * <p>Record framing belongs to the caller. For the canonical IF-05 JSON Lines
 * format, for example, a codec handles one JSON record while the store owns
 * LF/CRLF framing and incomplete-tail handling.</p>
 */
public interface TimingDataCodec {

    /** Encodes the shared scalar timing record directly. */
    byte[] encode(TimingRecord record) throws CodecException;

    /** Decodes one payload directly into the shared scalar timing record. */
    TimingRecord decodeRecord(byte[] encodedRecord) throws CodecException;

    /**
     * Legacy Java semantic view retained only for compatibility at API boundaries.
     */
    default byte[] encode(TimingData data) throws CodecException {
        return encode(TimingRecords.fromTimingData(data));
    }

    /**
     * Legacy Java semantic view retained only for compatibility at API boundaries.
     */
    default TimingData decode(byte[] encodedRecord) throws CodecException {
        return TimingRecords.asTimingData(decodeRecord(encodedRecord));
    }

    /**
     * Checked codec failure carrying semantic context for validation and
     * compatibility handling.
     *
     * <p>Consumers normally inspect {@link #reason()} first. Unsupported-version
     * and unsupported-record-type factories attach the common envelope values
     * that were safely readable without pretending the record was successfully
     * decoded.</p>
     */
    final class CodecException extends Exception {

        public enum Reason {
            INVALID_DATA,
            UNSUPPORTED_VERSION,
            UNSUPPORTED_RECORD_TYPE,
            ENCODE_FAILURE
        }

        private final Reason reason;
        private final Integer version;
        private final TimingData.RecordKey key;
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
                TimingData.RecordKey key,
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

        public static CodecException unsupportedVersion(
                int version,
                String message) {
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

        /**
         * Reports an unknown record type in an otherwise readable
         * supported-version envelope. Raw encoded bytes remain owned by the caller.
         */
        public static CodecException unsupportedRecordType(
                int version,
                TimingData.RecordKey key,
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
            if (recordedAt == null) {
                throw new IllegalArgumentException("recordedAt must not be null");
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

        public TimingData.RecordKey key() {
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
