package io.github.brainboxemb.eventtiming.timingdata;

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
     * <p>Within the TimingData API the shorter source name is sufficient; the
     * IF-05 semantic meaning remains TimingNode identity and the JSON member
     * remains {@code nodeId}.</p>
     */
    public static final class NodeId {
        private final String value;

        public NodeId(String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("NodeId must not be blank");
            }
            this.value = value.trim();
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
     * Shared representation of the registration identity carried by registration
     * TimingData.
     *
     * <p>The value is opaque to the shared library. Event/profile configuration
     * owns its concrete meaning and allowed value set.</p>
     */
    public static final class RegistrationId {
        private final String value;

        public RegistrationId(String value) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("value must not be blank");
            }
            this.value = value;
        }

        public String value() {
            return value;
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
}
