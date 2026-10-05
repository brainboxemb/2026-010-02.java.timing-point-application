package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaPowerControl;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaState;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Performs the physical antenna switching sequence.
 *
 * <p>This class contains the hardware-facing steps that should not obscure
 * {@link AntennaManager}: probe, external power, stabilization, initialize,
 * inventory start/stop, multiplex rotation and final close.</p>
 *
 * <p>It has no executor or timer dependency. AntennaManager serializes every
 * call into this controller on one control lane.</p>
 */
final class AntennaSwitchController {

    /** Mutable runtime state for one configured antenna. */
    private static final class ManagedAntenna {
        private final AntennaInstallation installation;
        private AntennaState state = AntennaState.UNCHECKED;
        private Throwable failure;
        private boolean externalPowerApplied;

        private ManagedAntenna(
                AntennaInstallation installation) {
            this.installation = installation;
        }
    }

    private final List<ManagedAntenna> antennas;
    private final List<ManagedAntenna> inventoryGroup;
    private final Duration inventoryInterval;

    private Throwable failure;

    AntennaSwitchController(
            List<AntennaInstallation> installations) {
        if (installations == null
                || installations.isEmpty()) {
            throw new IllegalArgumentException(
                    "installations must contain at least one antenna");
        }

        List<ManagedAntenna> configured =
                new ArrayList<ManagedAntenna>(
                        installations.size());
        List<ManagedAntenna> group =
                new ArrayList<ManagedAntenna>();
        Duration groupInterval = null;

        for (AntennaInstallation installation
                : installations) {
            validateInstallation(
                    installation,
                    configured);

            ManagedAntenna managed =
                    new ManagedAntenna(
                            installation);
            configured.add(managed);

            if (installation.inInventoryGroup()) {
                groupInterval =
                        sharedGroupInterval(
                                groupInterval,
                                installation.inventoryInterval());
                group.add(managed);
            }
        }

        if (group.size() == 1) {
            throw new IllegalArgumentException(
                    "inventory group requires at least two antennas");
        }

        antennas =
                Collections.unmodifiableList(
                        configured);
        inventoryGroup =
                Collections.unmodifiableList(
                        group);
        inventoryInterval = groupInterval;
    }

    EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return find(antennaId)
                .installation
                .antenna()
                .tagObservedEvent();
    }

    List<AntennaStatus> statuses() {
        List<AntennaStatus> result =
                new ArrayList<AntennaStatus>(
                        antennas.size());
        for (ManagedAntenna antenna : antennas) {
            result.add(snapshot(antenna));
        }
        return Collections.unmodifiableList(
                result);
    }

    AntennaStatus status(
            AntennaId antennaId) {
        return snapshot(
                find(antennaId));
    }

    Throwable failure() {
        return failure;
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

    boolean rotationNeeded() {
        return healthyCount(
                inventoryGroup) > 1;
    }

    /**
     * Probes every antenna without leaving inventory or external power enabled.
     */
    void probeAll() {
        for (ManagedAntenna antenna : antennas) {
            probe(antenna);
        }
    }

    /**
     * Enables tag inventory on every healthy independent antenna and on one
     * healthy member of the optional mutual-exclusion group.
     */
    void enableInventory() {
        for (ManagedAntenna antenna : antennas) {
            if (!antenna.installation.inInventoryGroup()
                    && prepareForInventory(antenna)) {
                startInventory(antenna);
            }
        }

        if (inventoryGroup.isEmpty()) {
            return;
        }

        for (ManagedAntenna antenna : inventoryGroup) {
            prepareForInventory(antenna);
        }
        startFirstAvailable(
                inventoryGroup);
    }

    /**
     * Stops all inventory and removes external power where configured.
     */
    void disableInventory() {
        for (int index = antennas.size() - 1;
                index >= 0;
                index--) {
            ManagedAntenna antenna =
                    antennas.get(index);
            stopInventory(antenna);
            powerOff(antenna);
        }
    }

    /**
     * Moves the inventory role to the next healthy multiplex-group antenna.
     */
    void rotateInventoryGroup() {
        if (inventoryGroup.isEmpty()) {
            return;
        }

        if (!rotationNeeded()) {
            startFirstAvailable(
                    inventoryGroup);
            return;
        }

        int currentIndex =
                currentInventoryIndex();
        if (currentIndex >= 0) {
            stopInventory(
                    inventoryGroup.get(
                            currentIndex));
        }

        int firstCandidate =
                currentIndex < 0
                        ? 0
                        : currentIndex + 1;

        for (int offset = 0;
                offset < inventoryGroup.size();
                offset++) {
            ManagedAntenna candidate =
                    inventoryGroup.get(
                            (firstCandidate + offset)
                                    % inventoryGroup.size());
            if (startInventory(candidate)) {
                return;
            }
        }
    }

    /**
     * Final hardware cleanup used when AntennaManager itself deactivates.
     */
    void closeAll() {
        RuntimeException firstFailure = null;

        for (int index = antennas.size() - 1;
                index >= 0;
                index--) {
            ManagedAntenna managed =
                    antennas.get(index);
            Antenna antenna =
                    managed.installation.antenna();

            try {
                stopInventory(managed);
            } catch (RuntimeException ex) {
                firstFailure =
                        firstFailure(
                                firstFailure,
                                ex);
            }

            try {
                powerOff(managed);
            } catch (RuntimeException ex) {
                firstFailure =
                        firstFailure(
                                firstFailure,
                                ex);
            }

            try {
                antenna.close();
            } catch (RuntimeException ex) {
                firstFailure =
                        firstFailure(
                                firstFailure,
                                ex);
            }

            managed.state =
                    AntennaState.CLOSED;
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    State aggregateState() {
        int healthy = 0;
        int failed = 0;

        for (ManagedAntenna antenna : antennas) {
            if (antenna.state == AntennaState.ERROR) {
                failed++;
            } else if (antenna.state != AntennaState.CLOSED
                    && antenna.state != AntennaState.UNCHECKED
                    && antenna.state != AntennaState.CHECKING) {
                healthy++;
            }
        }

        if (healthy == 0) {
            return State.FAILED;
        }
        return failed > 0
                ? State.DEGRADED
                : State.ACTIVE;
    }

    private void probe(
            ManagedAntenna antenna) {
        antenna.state =
                AntennaState.CHECKING;
        antenna.failure = null;

        try {
            powerOn(antenna);
            antenna.installation
                    .antenna()
                    .probe();
            antenna.state =
                    AntennaState.READY;
        } catch (RuntimeException ex) {
            fail(antenna, ex);
        } catch (Error ex) {
            fail(antenna, ex);
        } finally {
            powerOffAfterProbe(antenna);
        }
    }

    /**
     * Ensures the hardware is powered and initialized, but does not start
     * inventory yet.
     */
    private boolean prepareForInventory(
            ManagedAntenna antenna) {
        if (antenna.state == AntennaState.ERROR
                || antenna.state == AntennaState.CLOSED) {
            return false;
        }
        if (antenna.state == AntennaState.INVENTORY) {
            return true;
        }

        try {
            powerOn(antenna);
            antenna.installation
                    .antenna()
                    .initialize();
            antenna.state =
                    AntennaState.READY;
            return true;
        } catch (RuntimeException ex) {
            fail(antenna, ex);
            powerOffAfterFailure(antenna);
            return false;
        } catch (Error ex) {
            fail(antenna, ex);
            powerOffAfterFailure(antenna);
            return false;
        }
    }

    private boolean startInventory(
            ManagedAntenna antenna) {
        if (antenna.state == AntennaState.INVENTORY) {
            return true;
        }
        if (antenna.state != AntennaState.READY) {
            return false;
        }

        try {
            antenna.installation
                    .antenna()
                    .startInventory();
            antenna.state =
                    AntennaState.INVENTORY;
            return true;
        } catch (RuntimeException ex) {
            fail(antenna, ex);
            powerOffAfterFailure(antenna);
            return false;
        } catch (Error ex) {
            fail(antenna, ex);
            powerOffAfterFailure(antenna);
            return false;
        }
    }

    private void stopInventory(
            ManagedAntenna antenna) {
        if (antenna.state != AntennaState.INVENTORY) {
            return;
        }

        try {
            antenna.installation
                    .antenna()
                    .stopInventory();
            antenna.state =
                    AntennaState.READY;
        } catch (RuntimeException ex) {
            fail(antenna, ex);
        } catch (Error ex) {
            fail(antenna, ex);
        }
    }

    private void startFirstAvailable(
            List<ManagedAntenna> candidates) {
        for (ManagedAntenna antenna : candidates) {
            if (antenna.state == AntennaState.INVENTORY) {
                return;
            }
        }
        for (ManagedAntenna antenna : candidates) {
            if (startInventory(antenna)) {
                return;
            }
        }
    }

    private int currentInventoryIndex() {
        for (int index = 0;
                index < inventoryGroup.size();
                index++) {
            if (inventoryGroup.get(index).state
                    == AntennaState.INVENTORY) {
                return index;
            }
        }
        return -1;
    }

    private void powerOn(
            ManagedAntenna antenna) {
        AntennaPowerControl power =
                antenna.installation.powerControl();

        if (power == null
                || antenna.externalPowerApplied) {
            return;
        }

        power.powerOn();
        antenna.externalPowerApplied = true;
        waitForStabilization(
                antenna.installation
                        .powerStabilization());
    }

    private void powerOff(
            ManagedAntenna antenna) {
        AntennaPowerControl power =
                antenna.installation.powerControl();

        if (power == null
                || !antenna.externalPowerApplied) {
            return;
        }

        try {
            power.powerOff();
        } finally {
            antenna.externalPowerApplied = false;
        }
    }

    private void powerOffAfterProbe(
            ManagedAntenna antenna) {
        if (antenna.installation.powerControl()
                == null) {
            return;
        }
        try {
            powerOff(antenna);
        } catch (RuntimeException ex) {
            fail(antenna, ex);
        }
    }

    private void powerOffAfterFailure(
            ManagedAntenna antenna) {
        try {
            powerOff(antenna);
        } catch (RuntimeException ignored) {
            // Keep the original provider failure as the diagnostic cause.
        }
    }

    private static void waitForStabilization(
            Duration delay) {
        if (delay.isZero()) {
            return;
        }

        try {
            TimeUnit.NANOSECONDS.sleep(
                    delay.toNanos());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Antenna power stabilization was interrupted",
                    ex);
        }
    }

    private void fail(
            ManagedAntenna antenna,
            Throwable cause) {
        if (antenna.failure == null) {
            antenna.failure = cause;
        }
        antenna.state =
                AntennaState.ERROR;
        if (failure == null) {
            failure = cause;
        }
    }

    private ManagedAntenna find(
            AntennaId antennaId) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }

        for (ManagedAntenna antenna : antennas) {
            if (antenna.installation
                    .antennaId()
                    .equals(antennaId)) {
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
            if (antenna.installation
                    .antennaId()
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
        if (!current.equals(candidate)) {
            throw new IllegalArgumentException(
                    "all antennas in the inventory group "
                            + "must use the same interval");
        }
        return current;
    }

    private static int healthyCount(
            List<ManagedAntenna> antennas) {
        int count = 0;
        for (ManagedAntenna antenna : antennas) {
            if (antenna.state == AntennaState.READY
                    || antenna.state == AntennaState.INVENTORY) {
                count++;
            }
        }
        return count;
    }

    private static AntennaStatus snapshot(
            ManagedAntenna antenna) {
        return new AntennaStatus(
                antenna.installation.antennaId(),
                antenna.state,
                antenna.failure);
    }

    private static RuntimeException firstFailure(
            RuntimeException current,
            RuntimeException candidate) {
        if (current == null) {
            return candidate;
        }
        current.addSuppressed(candidate);
        return current;
    }
}
