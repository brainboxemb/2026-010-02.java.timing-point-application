package oss.relay.timing.timingdata;

/**
 * Shared value types used by the TimingData boundary.
 *
 * <p>This class is only a Java source-code grouping. It has no runtime state,
 * lifecycle or architecture responsibility of its own.</p>
 */
public final class TimingDataTypes {
    private TimingDataTypes() {
    }

    /**
     * Stable configured software/source identity of one TimingNode.
     *
     * <p>A TimingNodeId is exactly one character: {@code A} through {@code Z}
     * or {@code 1} through {@code 9}. Within the TimingData API the shorter
     * source name is sufficient; the IF-05 semantic meaning remains TimingNode
     * identity and the JSON member remains {@code nodeId}.</p>
     */
    public static final class NodeId {
        private final String value;

        public NodeId(String value) {
            String normalized = value == null ? null : value.trim();
            if (normalized == null
                    || normalized.length() != 1
                    || !isTimingNodeIdCharacter(normalized.charAt(0))) {
                throw new IllegalArgumentException(
                        "TimingNodeId must be one character A-Z or 1-9");
            }
            this.value = normalized;
        }

        private static boolean isTimingNodeIdCharacter(char value) {
            return value >= 'A' && value <= 'Z'
                    || value >= '1' && value <= '9';
        }

        public String value() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NodeId)) {
                return false;
            }
            NodeId that = (NodeId) other;
            return value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Shared representation of a timing-location identity.
     *
     * <p>The numeric shape is common TimingData/IF-05 structure. Concrete
     * event/profile configuration owns the allowed value set and meaning.</p>
     */
    public static final class LocationId {
        private final int value;

        public LocationId(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("LocationId must be positive");
            }
            this.value = value;
        }

        public int value() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LocationId)) {
                return false;
            }
            LocationId that = (LocationId) other;
            return value == that.value;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(value);
        }

        @Override
        public String toString() {
            return Integer.toString(value);
        }
    }

    /**
     * Registration identity carried by registration TimingData.
     *
     * <p>The canonical text shape is one type character {@code A-Z} followed by
     * exactly four decimal digits. The value is retained as one packed integer,
     * while event/profile configuration owns the meaning and allowed set of
     * concrete type/number combinations.</p>
     */
    public static final class RegistrationId {
        private static final int NUMBER_SHIFT = 16;
        private static final int NUMBER_MASK = 0xFFFF;
        private static final int MAX_NUMBER = 9999;

        private final int packedValue;

        public RegistrationId(String value) {
            if (value == null
                    || value.length() != 5
                    || !isType(value.charAt(0))
                    || !isDigit(value.charAt(1))
                    || !isDigit(value.charAt(2))
                    || !isDigit(value.charAt(3))
                    || !isDigit(value.charAt(4))) {
                throw new IllegalArgumentException(
                        "RegistrationId must be one character A-Z followed by four digits");
            }

            packedValue =
                    pack(
                            value.charAt(0),
                            (value.charAt(1) - '0') * 1000
                                    + (value.charAt(2) - '0') * 100
                                    + (value.charAt(3) - '0') * 10
                                    + value.charAt(4) - '0');
        }

        public RegistrationId(
                char type,
                int number) {
            packedValue = pack(type, number);
        }

        private RegistrationId(
                int packedValue) {
            char type = unpackType(packedValue);
            int number = unpackNumber(packedValue);
            if (!isType(type) || number < 0 || number > MAX_NUMBER) {
                throw new IllegalArgumentException("invalid packed RegistrationId");
            }
            this.packedValue = packedValue;
        }

        public static RegistrationId fromPackedValue(
                int packedValue) {
            return new RegistrationId(packedValue);
        }

        public int packedValue() {
            return packedValue;
        }

        public char type() {
            return unpackType(packedValue);
        }

        public int number() {
            return unpackNumber(packedValue);
        }

        public String value() {
            int number = number();
            char[] value = new char[5];
            value[0] = type();
            value[1] = (char) ('0' + number / 1000);
            value[2] = (char) ('0' + number / 100 % 10);
            value[3] = (char) ('0' + number / 10 % 10);
            value[4] = (char) ('0' + number % 10);
            return new String(value);
        }

        private static int pack(
                char type,
                int number) {
            if (!isType(type)) {
                throw new IllegalArgumentException(
                        "RegistrationId type must be A-Z");
            }
            if (number < 0 || number > MAX_NUMBER) {
                throw new IllegalArgumentException(
                        "RegistrationId number must be in range 0000..9999");
            }
            return type << NUMBER_SHIFT
                    | number;
        }

        private static char unpackType(
                int packedValue) {
            return (char) (packedValue >>> NUMBER_SHIFT);
        }

        private static int unpackNumber(
                int packedValue) {
            return packedValue & NUMBER_MASK;
        }

        private static boolean isType(
                char value) {
            return value >= 'A' && value <= 'Z';
        }

        private static boolean isDigit(
                char value) {
            return value >= '0' && value <= '9';
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RegistrationId)) {
                return false;
            }
            RegistrationId that = (RegistrationId) other;
            return packedValue == that.packedValue;
        }

        @Override
        public int hashCode() {
            return Integer.hashCode(packedValue);
        }

        @Override
        public String toString() {
            return value();
        }
    }
}
