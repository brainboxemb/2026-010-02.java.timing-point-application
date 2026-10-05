package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import java.time.Duration;

/** Immutable installation policy bound to one configured antenna. */
public final class AntennaInstallation {
    private final Antenna antenna;
    private final AntennaPowerControl powerControl;
    private final Duration powerStabilization;
    private final String inventoryGroup;
    private final Duration inventoryInterval;

    public AntennaInstallation(
            Antenna antenna,
            AntennaPowerControl powerControl,
            Duration powerStabilization,
            String inventoryGroup,
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

        String normalizedGroup = normalizeGroup(inventoryGroup);
        if (normalizedGroup == null && inventoryInterval != null) {
            throw new IllegalArgumentException(
                    "inventoryInterval requires inventoryGroup");
        }
        if (normalizedGroup != null
                && (inventoryInterval == null
                    || inventoryInterval.isZero()
                    || inventoryInterval.isNegative())) {
            throw new IllegalArgumentException(
                    "inventoryInterval must be positive for an inventory group");
        }

        this.antenna = antenna;
        this.powerControl = powerControl;
        this.powerStabilization = powerStabilization;
        this.inventoryGroup = normalizedGroup;
        this.inventoryInterval = inventoryInterval;
    }

    public static AntennaInstallation direct(Antenna antenna) {
        return new AntennaInstallation(
                antenna,
                null,
                Duration.ZERO,
                null,
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
                null,
                null);
    }

    public AntennaInstallation inInventoryGroup(
            String group,
            Duration interval) {
        return new AntennaInstallation(
                antenna,
                powerControl,
                powerStabilization,
                group,
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

    public String inventoryGroup() {
        return inventoryGroup;
    }

    public Duration inventoryInterval() {
        return inventoryInterval;
    }

    private static String normalizeGroup(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "inventoryGroup must not be blank");
        }
        return normalized;
    }
}
