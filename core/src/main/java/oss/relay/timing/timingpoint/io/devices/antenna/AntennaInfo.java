package oss.relay.timing.timingpoint.io.devices.antenna;

/** Immutable result of an antenna identity/version probe. */
public final class AntennaInfo {
    private final String identity;
    private final String version;

    /** Creates one immutable validated antenna identity/version result. */
    public AntennaInfo(String identity, String version) {
        if (identity == null || identity.trim().isEmpty()) {
            throw new IllegalArgumentException("identity must not be blank");
        }
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        this.identity = identity.trim();
        this.version = version.trim();
    }

    /** Returns the hardware/provider identity reported by the antenna. */
    public String identity() {
        return identity;
    }

    /** Returns the hardware/provider version reported by the antenna. */
    public String version() {
        return version;
    }
}
