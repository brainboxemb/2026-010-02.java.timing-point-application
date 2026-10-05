package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaPowerControl;

import java.time.Duration;

/**
 * Immutable configuration of one antenna installation.
 *
 * <p>The configured {@link AntennaId} is visible outside the manager package.
 * The concrete {@link Antenna} and optional power switch stay package-private:
 * AntennaManager owns those device objects after composition.</p>
 */
public final class AntennaInstallation {
    private final AntennaId antennaId;
    private final Antenna antenna;
    private final AntennaPowerControl powerControl;
    private final Duration powerStabilization;
    private final Duration inventoryInterval;

    public AntennaInstallation(
            AntennaId antennaId,
            Antenna antenna,
            AntennaPowerControl powerControl,
            Duration powerStabilization,
            Duration inventoryInterval) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }
        if (antenna == null) {
            throw new IllegalArgumentException(
                    "antenna must not be null");
        }
        if (powerStabilization == null
                || powerStabilization.isNegative()) {
            throw new IllegalArgumentException(
                    "powerStabilization must not be negative");
        }
        if (powerControl == null
                && !powerStabilization.isZero()) {
            throw new IllegalArgumentException(
                    "powerStabilization requires external power control");
        }
        if (inventoryInterval != null
                && (inventoryInterval.isZero()
                    || inventoryInterval.isNegative())) {
            throw new IllegalArgumentException(
                    "inventoryInterval must be positive");
        }

        this.antennaId = antennaId;
        this.antenna = antenna;
        this.powerControl = powerControl;
        this.powerStabilization = powerStabilization;
        this.inventoryInterval = inventoryInterval;
    }

    public static AntennaInstallation direct(
            AntennaId antennaId,
            Antenna antenna) {
        return new AntennaInstallation(
                antennaId,
                antenna,
                null,
                Duration.ZERO,
                null);
    }

    public static AntennaInstallation powered(
            AntennaId antennaId,
            Antenna antenna,
            AntennaPowerControl powerControl,
            Duration powerStabilization) {
        return new AntennaInstallation(
                antennaId,
                antenna,
                powerControl,
                powerStabilization,
                null);
    }

    /**
     * Adds this installation to the manager's optional mutual-exclusion group.
     */
    public AntennaInstallation inInventoryGroup(
            Duration interval) {
        return new AntennaInstallation(
                antennaId,
                antenna,
                powerControl,
                powerStabilization,
                interval);
    }

    public AntennaId antennaId() {
        return antennaId;
    }

    Antenna antenna() {
        return antenna;
    }

    AntennaPowerControl powerControl() {
        return powerControl;
    }

    Duration powerStabilization() {
        return powerStabilization;
    }

    boolean inInventoryGroup() {
        return inventoryInterval != null;
    }

    Duration inventoryInterval() {
        return inventoryInterval;
    }
}
