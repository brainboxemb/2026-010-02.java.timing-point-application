package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.ScheduledTaskRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configured runtime antenna set owned by one AntennaManager.
 *
 * <p>This object owns collection construction, identity lookup, status
 * aggregation and immutable inventory-group configuration. It owns no control
 * logic, scheduler or switching state.</p>
 */
final class ManagedAntennaSet {

    private final List<ManagedAntenna> antennas;
    private final List<ManagedAntenna> inventoryGroup;
    private final Duration inventoryInterval;

    ManagedAntennaSet(
            List<AntennaInstallation> installations,
            ScheduledTaskRunner taskRunner) {
        if (installations == null
                || installations.isEmpty()) {
            throw new IllegalArgumentException(
                    "installations must contain at least one antenna");
        }

        List<ManagedAntenna> configured =
                new ArrayList<ManagedAntenna>(
                        installations.size());
        List<ManagedAntenna> configuredGroup =
                new ArrayList<ManagedAntenna>();
        Duration configuredInterval = null;

        for (AntennaInstallation installation : installations) {
            validateInstallation(
                    installation,
                    configured);

            ManagedAntenna antenna =
                    new ManagedAntenna(
                            installation,
                            taskRunner);
            configured.add(
                    antenna);

            if (antenna.inInventoryGroup()) {
                configuredInterval =
                        sharedGroupInterval(
                                configuredInterval,
                                antenna.inventoryInterval());
                configuredGroup.add(
                        antenna);
            }
        }

        if (!configuredGroup.isEmpty()
                && configuredGroup.size() < 2) {
            throw new IllegalArgumentException(
                    "inventory group requires at least two antennas");
        }

        antennas =
                Collections.unmodifiableList(
                        configured);
        inventoryGroup =
                Collections.unmodifiableList(
                        configuredGroup);
        inventoryInterval =
                configuredInterval;
    }

    List<ManagedAntenna> antennas() {
        return antennas;
    }

    List<ManagedAntenna> inventoryGroup() {
        return inventoryGroup;
    }

    boolean hasInventoryGroup() {
        return !inventoryGroup.isEmpty();
    }

    Duration inventoryInterval() {
        if (inventoryInterval == null) {
            throw new IllegalStateException(
                    "no inventory group is configured");
        }
        return inventoryInterval;
    }

    int size() {
        return antennas.size();
    }

    boolean allSelfTestsPassed() {
        for (ManagedAntenna antenna : antennas) {
            if (!antenna.selfTestPassed()) {
                return false;
            }
        }
        return true;
    }

    EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return find(
                antennaId)
                .tagObservedEvent();
    }

    List<AntennaStatus> statuses() {
        List<AntennaStatus> result =
                new ArrayList<AntennaStatus>(
                        antennas.size());
        for (ManagedAntenna antenna : antennas) {
            result.add(
                    antenna.status());
        }
        return Collections.unmodifiableList(
                result);
    }

    AntennaStatus status(
            AntennaId antennaId) {
        return find(
                antennaId)
                .status();
    }

    private ManagedAntenna find(
            AntennaId antennaId) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }

        for (ManagedAntenna antenna : antennas) {
            if (antenna.antennaId()
                    .equals(
                            antennaId)) {
                return antenna;
            }
        }

        throw new IllegalArgumentException(
                "unknown AntennaId "
                        + antennaId);
    }

    private static void validateInstallation(
            AntennaInstallation installation,
            List<ManagedAntenna> existing) {
        if (installation == null) {
            throw new IllegalArgumentException(
                    "installations must not contain null");
        }

        for (ManagedAntenna antenna : existing) {
            if (antenna.antennaId()
                    .equals(
                            installation.antennaId())) {
                throw new IllegalArgumentException(
                        "duplicate AntennaId "
                                + installation.antennaId());
            }
        }
    }

    private static Duration sharedGroupInterval(
            Duration current,
            Duration candidate) {
        if (current == null) {
            return candidate;
        }
        if (!current.equals(
                candidate)) {
            throw new IllegalArgumentException(
                    "all antennas in the inventory group must use the same interval");
        }
        return current;
    }
}
