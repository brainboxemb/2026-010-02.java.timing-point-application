package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.AntennaState;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Package-private mutable antenna lifecycle logic.
 *
 * <p>Execution ordering and timeout/admission policy belong to AntennaManager.
 * This class owns only antenna, power and single-group multiplex state.</p>
 */
final class AntennaManagerLogic {

    private static final class ManagedAntenna {
        private final AntennaInstallation installation;
        private volatile AntennaState state =
                AntennaState.UNCHECKED;
        private volatile Throwable failure;
        private boolean externalPowerApplied;

        private ManagedAntenna(
                AntennaInstallation installation) {
            this.installation = installation;
        }
    }

    private final List<ManagedAntenna> managedAntennas;
    private final List<AntennaId> antennaIds;
    private final List<ManagedAntenna> inventoryGroup;
    private final Duration inventoryInterval;

    private volatile Throwable failure;

    AntennaManagerLogic(
            List<AntennaInstallation> installations) {
        if (installations == null
                || installations.isEmpty()) {
            throw new IllegalArgumentException(
                    "installations must contain at least one antenna");
        }

        List<ManagedAntenna> managed =
                new ArrayList<ManagedAntenna>(
                        installations.size());
        List<AntennaId> antennaIdCopy =
                new ArrayList<AntennaId>(
                        installations.size());
        List<ManagedAntenna> group =
                new ArrayList<ManagedAntenna>();

        Duration groupInterval = null;

        for (AntennaInstallation installation
                : installations) {
            if (installation == null) {
                throw new IllegalArgumentException(
                        "installations must not contain null");
            }

            for (ManagedAntenna existing : managed) {
                if (existing.installation.antennaId().equals(
                        installation.antennaId())) {
                    throw new IllegalArgumentException(
                            "duplicate AntennaId "
                                    + installation.antennaId());
                }
            }

            ManagedAntenna managedAntenna =
                    new ManagedAntenna(installation);
            managed.add(managedAntenna);
            antennaIdCopy.add(installation.antennaId());

            if (installation.inInventoryGroup()) {
                if (groupInterval == null) {
                    groupInterval =
                            installation.inventoryInterval();
                } else if (!groupInterval.equals(
                        installation.inventoryInterval())) {
                    throw new IllegalArgumentException(
                            "all antennas in the inventory group "
                                    + "must use the same interval");
                }
                group.add(managedAntenna);
            }
        }

        if (group.size() == 1) {
            throw new IllegalArgumentException(
                    "inventory group requires at least two antennas");
        }

        managedAntennas =
                Collections.unmodifiableList(managed);
        antennaIds =
                Collections.unmodifiableList(antennaIdCopy);
        inventoryGroup =
                Collections.unmodifiableList(group);
        inventoryInterval = groupInterval;
    }

    List<AntennaId> antennaIds() {
        return antennaIds;
    }

    EventSource<TagObservation> tagObservedEvent(
            AntennaId antennaId) {
        return managedAntenna(antennaId)
                .installation
                .antenna()
                .tagObservedEvent();
    }

    List<AntennaStatus> statuses() {
        List<AntennaStatus> result =
                new ArrayList<AntennaStatus>(
                        managedAntennas.size());
        for (ManagedAntenna managed : managedAntennas) {
            result.add(snapshot(managed));
        }
        return Collections.unmodifiableList(result);
    }

    AntennaStatus status(
            AntennaId antennaId) {
        return snapshot(
                managedAntenna(antennaId));
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

    boolean inventoryGroupNeedsRotation() {
        return healthyMemberCount(inventoryGroup) > 1;
    }

    void probeAll() {
        for (ManagedAntenna managed : managedAntennas) {
            sanityCheck(managed);
        }
    }

    void activateAvailableAntennas() {
        for (ManagedAntenna managed : managedAntennas) {
            if (!managed.installation.inInventoryGroup()) {
                if (prepareForOperation(managed)) {
                    startInventory(managed);
                }
            }
        }

        if (!inventoryGroup.isEmpty()) {
            for (ManagedAntenna managed : inventoryGroup) {
                prepareForOperation(managed);
            }
            startFirstAvailable(inventoryGroup);
        }
    }

    void deactivateAntennas() {
        for (int index = managedAntennas.size() - 1;
                index >= 0;
                index--) {
            ManagedAntenna managed =
                    managedAntennas.get(index);

            if (managed.state
                    == AntennaState.INVENTORY) {
                try {
                    managed.installation
                            .antenna()
                            .stopInventory();
                    managed.state =
                            AntennaState.READY;
                } catch (RuntimeException ex) {
                    markAntennaFailed(managed, ex);
                } catch (Error ex) {
                    markAntennaFailed(managed, ex);
                }
            }

            if (managed.installation.powerControl()
                    != null) {
                try {
                    powerOffIfConfigured(managed);
                } catch (RuntimeException ex) {
                    markAntennaFailed(managed, ex);
                } catch (Error ex) {
                    markAntennaFailed(managed, ex);
                }
            }
        }
    }

    void rotateInventoryGroup() {
        if (inventoryGroup.isEmpty()) {
            return;
        }

        int healthy =
                healthyMemberCount(inventoryGroup);
        if (healthy <= 1) {
            startFirstAvailable(inventoryGroup);
            return;
        }

        int currentIndex = -1;
        for (int index = 0;
                index < inventoryGroup.size();
                index++) {
            if (inventoryGroup.get(index).state
                    == AntennaState.INVENTORY) {
                currentIndex = index;
                break;
            }
        }

        if (currentIndex >= 0) {
            ManagedAntenna current =
                    inventoryGroup.get(currentIndex);
            try {
                current.installation
                        .antenna()
                        .stopInventory();
                current.state =
                        AntennaState.READY;
            } catch (RuntimeException ex) {
                markAntennaFailed(current, ex);
                powerOffAfterFailure(current);
            } catch (Error ex) {
                markAntennaFailed(current, ex);
                powerOffAfterFailure(current);
            }
        }

        int startIndex =
                currentIndex < 0
                        ? 0
                        : currentIndex + 1;
        for (int offset = 0;
                offset < inventoryGroup.size();
                offset++) {
            ManagedAntenna candidate =
                    inventoryGroup.get(
                            (startIndex + offset)
                                    % inventoryGroup.size());
            if (startInventory(candidate)) {
                break;
            }
        }
    }

    void stopPowerAndCloseAll() {
        RuntimeException firstFailure = null;

        for (int index = managedAntennas.size() - 1;
                index >= 0;
                index--) {
            ManagedAntenna managed =
                    managedAntennas.get(index);
            Antenna antenna =
                    managed.installation.antenna();

            try {
                if (antenna.inventoryRunning()) {
                    antenna.stopInventory();
                }
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }

            try {
                powerOffIfConfigured(managed);
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }

            try {
                antenna.close();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }

            managed.state = AntennaState.CLOSED;
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    State aggregateState() {
        int healthy = 0;
        int failed = 0;

        for (ManagedAntenna managed : managedAntennas) {
            if (managed.state
                    == AntennaState.ERROR) {
                failed++;
            } else if (managed.state
                    != AntennaState.CLOSED
                    && managed.state
                    != AntennaState.UNCHECKED
                    && managed.state
                    != AntennaState.CHECKING) {
                healthy++;
            }
        }

        if (healthy == 0) {
            return State.FAILED;
        }
        if (failed > 0) {
            return State.DEGRADED;
        }
        return State.RUNNING;
    }

    private void sanityCheck(
            ManagedAntenna managed) {
        managed.state = AntennaState.CHECKING;
        managed.failure = null;

        try {
            powerOnIfConfigured(managed);
            managed.installation.antenna().probe();
            managed.state = AntennaState.READY;
        } catch (RuntimeException ex) {
            markAntennaFailed(managed, ex);
        } catch (Error ex) {
            markAntennaFailed(managed, ex);
        } finally {
            if (managed.installation.powerControl()
                    != null) {
                try {
                    powerOffIfConfigured(managed);
                } catch (RuntimeException ex) {
                    markAntennaFailed(managed, ex);
                } catch (Error ex) {
                    markAntennaFailed(managed, ex);
                }
            }
        }
    }

    private boolean prepareForOperation(
            ManagedAntenna managed) {
        if (managed.state == AntennaState.ERROR
                || managed.state
                    == AntennaState.CLOSED) {
            return false;
        }
        if (managed.state
                == AntennaState.INVENTORY) {
            return true;
        }

        try {
            powerOnIfConfigured(managed);
            managed.installation
                    .antenna()
                    .initialize();
            managed.state = AntennaState.READY;
            return true;
        } catch (RuntimeException ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        } catch (Error ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        }
    }

    private boolean startInventory(
            ManagedAntenna managed) {
        if (managed.state
                == AntennaState.INVENTORY) {
            return true;
        }
        if (managed.state
                != AntennaState.READY) {
            return false;
        }

        try {
            managed.installation
                    .antenna()
                    .startInventory();
            managed.state =
                    AntennaState.INVENTORY;
            return true;
        } catch (RuntimeException ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        } catch (Error ex) {
            markAntennaFailed(managed, ex);
            powerOffAfterFailure(managed);
            return false;
        }
    }

    private void startFirstAvailable(
            List<ManagedAntenna> members) {
        for (ManagedAntenna managed : members) {
            if (managed.state
                    == AntennaState.INVENTORY) {
                return;
            }
        }

        for (ManagedAntenna managed : members) {
            if (startInventory(managed)) {
                return;
            }
        }
    }

    private static int healthyMemberCount(
            List<ManagedAntenna> members) {
        int count = 0;
        for (ManagedAntenna managed : members) {
            if (managed.state == AntennaState.READY
                    || managed.state
                    == AntennaState.INVENTORY) {
                count++;
            }
        }
        return count;
    }

    private void powerOnIfConfigured(
            ManagedAntenna managed) {
        AntennaPowerControl power =
                managed.installation.powerControl();
        if (power == null
                || managed.externalPowerApplied) {
            return;
        }

        power.powerOn();
        managed.externalPowerApplied = true;
        waitForStabilization(
                managed.installation
                        .powerStabilization());
    }

    private void powerOffIfConfigured(
            ManagedAntenna managed) {
        AntennaPowerControl power =
                managed.installation.powerControl();
        if (power == null
                || !managed.externalPowerApplied) {
            return;
        }

        try {
            power.powerOff();
        } finally {
            managed.externalPowerApplied = false;
        }
    }

    private void powerOffAfterFailure(
            ManagedAntenna managed) {
        if (managed.installation.powerControl()
                == null) {
            return;
        }

        try {
            powerOffIfConfigured(managed);
        } catch (RuntimeException ignored) {
            // Preserve the first antenna failure as the diagnostic cause.
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

    private void markAntennaFailed(
            ManagedAntenna managed,
            Throwable cause) {
        if (managed.failure == null) {
            managed.failure = cause;
        }
        managed.state = AntennaState.ERROR;
        if (failure == null) {
            failure = cause;
        }
    }

    private ManagedAntenna managedAntenna(
            AntennaId antennaId) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }
        for (ManagedAntenna managed : managedAntennas) {
            if (managed.installation.antennaId()
                    .equals(antennaId)) {
                return managed;
            }
        }
        throw new IllegalArgumentException(
                "unknown AntennaId " + antennaId);
    }

    private static AntennaStatus snapshot(
            ManagedAntenna managed) {
        return new AntennaStatus(
                managed.installation.antennaId(),
                managed.state,
                managed.failure);
    }
}
