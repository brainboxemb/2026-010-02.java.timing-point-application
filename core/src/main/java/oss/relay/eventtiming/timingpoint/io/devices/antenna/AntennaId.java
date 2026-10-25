package oss.relay.eventtiming.timingpoint.io.devices.antenna;

/**
 * Configured software identity of one antenna.
 *
 * <p>An AntennaId is exactly one digit: {@code 1} through {@code 9}. It is the
 * stable identity used by composition, routing and status and is distinct from
 * provider/hardware identity returned by the antenna.</p>
 */
public final class AntennaId {
    private final String value;

    /** Creates one validated configured antenna identity. */
    public AntennaId(String value) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null
                || normalized.length() != 1
                || normalized.charAt(0) < '1'
                || normalized.charAt(0) > '9') {
            throw new IllegalArgumentException(
                    "AntennaId must be one digit 1-9");
        }
        this.value = normalized;
    }

    /** Returns the normalized one-digit configured antenna identity. */
    public String value() {
        return value;
    }

    /** Compares AntennaId values by normalized identity. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AntennaId)) {
            return false;
        }
        AntennaId that = (AntennaId) other;
        return value.equals(that.value);
    }

    /** Returns a hash consistent with AntennaId value equality. */
    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Returns the configured antenna identity for diagnostics/configuration text. */
    @Override
    public String toString() {
        return value;
    }
}
