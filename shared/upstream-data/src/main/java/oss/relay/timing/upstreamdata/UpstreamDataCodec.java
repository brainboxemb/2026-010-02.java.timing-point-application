package oss.relay.timing.upstreamdata;

/**
 * Encodes or decodes one complete UpstreamData semantic message.
 *
 * <p>Transport framing, routing and session metadata stay outside this codec.</p>
 */
public interface UpstreamDataCodec {

    byte[] encode(UpstreamData data) throws CodecException;

    UpstreamData decode(byte[] encodedMessage) throws CodecException;

    final class CodecException extends Exception {
        public enum Reason {
            INVALID_DATA,
            UNSUPPORTED_VERSION,
            UNSUPPORTED_MESSAGE_TYPE,
            UNSUPPORTED_DATASET,
            ENCODE_FAILURE
        }

        private final Reason reason;
        private final Integer version;
        private final String messageType;
        private final String dataset;

        public CodecException(Reason reason, String message) {
            this(reason, message, null, null, null, null);
        }

        public CodecException(Reason reason, String message, Throwable cause) {
            this(reason, message, cause, null, null, null);
        }

        private CodecException(
                Reason reason,
                String message,
                Throwable cause,
                Integer version,
                String messageType,
                String dataset) {
            super(message, cause);
            if (reason == null) {
                throw new IllegalArgumentException("reason must not be null");
            }
            this.reason = reason;
            this.version = version;
            this.messageType = messageType;
            this.dataset = dataset;
        }

        public static CodecException unsupportedVersion(int version, String message) {
            return new CodecException(
                    Reason.UNSUPPORTED_VERSION, message, null, version, null, null);
        }

        public static CodecException unsupportedMessageType(
                int version, String messageType, String message) {
            return new CodecException(
                    Reason.UNSUPPORTED_MESSAGE_TYPE,
                    message,
                    null,
                    version,
                    messageType,
                    null);
        }

        public static CodecException unsupportedDataset(
                int version, String dataset, String message) {
            return new CodecException(
                    Reason.UNSUPPORTED_DATASET,
                    message,
                    null,
                    version,
                    "REFERENCE_DATA",
                    dataset);
        }

        public Reason reason() {
            return reason;
        }

        public Integer version() {
            return version;
        }

        public String messageType() {
            return messageType;
        }

        public String dataset() {
            return dataset;
        }
    }
}
