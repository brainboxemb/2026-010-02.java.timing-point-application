package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import java.time.Duration;

/** Immutable installation policy bound to one configured antenna. */
public final class AntennaInstallation {
    private final Antenna antenna;
    private final AntennaPowerControl powerControl;
    private final Duration powerStabilization;
    private final Duration inventoryInterval;

    public AntennaInstallation(
            Antenna antenna,
            AntennaPowerControl powerControl,
            Duration powerStabilization,
            Duration inventoryInterval) {
        if (antenna == null) {
            throw new IllegalArgumentException("antenna must not be null");
        }
        if (powerStabilization == null
                || powerStabilization.isNegative()) {
            throw new IllegalArgumentException(
                    "powerStabilization must not be negative");
        }
        if (powerControl == null && !powerStabilization.isZero()) {
            throw new IllegalArgumentException(
                    "powerStabilization requires external power control");
        }
        if (inventoryInterval != null
                && (inventoryInterval.isZero()
                    || inventoryInterval.isNegative())) {
            throw new IllegalArgumentException(
                    "inventoryInterval must be positive");
        }

        this.antenna = antenna;
        this.powerControl = powerControl;
        this.powerStabilization = powerStabilization;
        this.inventoryInterval = inventoryInterval;
    }

    public static AntennaInstallation direct(Antenna antenna) {
        return new AntennaInstallation(
                antenna,
                null,
                Duration.ZERO,
                null);
    }

    public static AntennaInstallation powered(
            Antenna antenna,
            AntennaPowerControl powerControl,
            Duration powerStabilization) {
        return new AntennaInstallation(
                antenna,
                powerControl,
                powerStabilization,
                null);
    }

    /**
     * Marks this antenna as a member of the manager's one optional
     * mutual-exclusion inventory group.
     */
    public AntennaInstallation inInventoryGroup(
            Duration interval) {
        return new AntennaInstallation(
                antenna,
                powerControl,
                powerStabilization,
                interval);
    }

    public Antenna antenna() {
        return antenna;
    }

    public AntennaPowerControl powerControl() {
        return powerControl;
    }

    public Duration powerStabilization() {
        return powerStabilization;
    }

    public boolean inInventoryGroup() {
        return inventoryInterval != null;
    }

    public Duration inventoryInterval() {
        return inventoryInterval;
    }
}
