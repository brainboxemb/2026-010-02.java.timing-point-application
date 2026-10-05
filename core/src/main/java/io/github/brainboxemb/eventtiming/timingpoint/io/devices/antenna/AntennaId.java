package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

/**
 * Configured software identity of one antenna installation.
 *
 * <p>AntennaId is the stable identity used by composition, routing and status.
 * It is distinct from provider/hardware identity returned by an antenna probe.</p>
 */
public final class AntennaId {
    private final String value;

    public AntennaId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "AntennaId must not be blank");
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
