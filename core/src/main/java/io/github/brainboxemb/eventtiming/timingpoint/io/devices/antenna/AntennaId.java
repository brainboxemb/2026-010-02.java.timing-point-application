package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/**
 * Configured software identity of one antenna.
 *
 * <p>An AntennaId is exactly one digit: {@code 1} through {@code 9}. It is the
 * stable identity used by composition, routing and status and is distinct from
 * provider/hardware identity returned by the antenna.</p>
 */
public final class AntennaId {
    private final String value;

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

    public String value() {
        return value;
    }

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

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
