package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Configured runtime antenna set owned by one AntennaManager.
 *
 * <p>This object owns set construction, identity lookup and status aggregation.
 * It owns no execution lane and performs no multi-step device operation.</p>
 */
final class ManagedAntennaSet {

    private final List<ManagedAntenna> antennas;
    private final AntennaSwitchController switching;

    ManagedAntennaSet(
            List<AntennaInstallation> installations) {
        if (installations == null
                || installations.isEmpty()) {
            throw new IllegalArgumentException(
                    "installations must contain at least one antenna");
        }

        List<ManagedAntenna> configured =
                new ArrayList<ManagedAntenna>(
                        installations.size());
        List<ManagedAntenna> inventoryGroup =
                new ArrayList<ManagedAntenna>();
        Duration groupInterval = null;

        for (AntennaInstallation installation : installations) {
            validateInstallation(
                    installation,
                    configured);

            ManagedAntenna antenna =
                    new ManagedAntenna(
                            installation);
            configured.add(
                    antenna);

            if (antenna.inInventoryGroup()) {
                groupInterval =
                        sharedGroupInterval(
                                groupInterval,
                                antenna.inventoryInterval());
                inventoryGroup.add(
                        antenna);
            }
        }

        antennas =
                Collections.unmodifiableList(
                        configured);
        switching =
                new AntennaSwitchController(
                        inventoryGroup,
                        groupInterval);
    }

    List<ManagedAntenna> antennas() {
        return antennas;
    }

    AntennaSwitchController switching() {
        return switching;
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
