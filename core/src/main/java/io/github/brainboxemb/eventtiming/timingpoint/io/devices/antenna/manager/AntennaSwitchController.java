package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Performs only mutual-exclusion inventory switching for one configured group.
 *
 * <p>AntennaManager owns antenna creation, startup self-test, power/initialize
 * sequencing, status and recovery decisions. This helper only knows the group
 * members that may not inventory at the same time and the round-robin interval.</p>
 *
 * <p>All methods run on the AntennaManager serial control lane. The helper owns
 * no worker, scheduler or synchronization.</p>
 */
final class AntennaSwitchController implements AntennaTasks.SwitchTarget {

    private final List<ManagedAntenna> inventoryGroup;
    private final Duration inventoryInterval;

    AntennaSwitchController(
            List<ManagedAntenna> inventoryGroup,
            Duration inventoryInterval) {
        if (inventoryGroup == null) {
            throw new IllegalArgumentException(
                    "inventoryGroup must not be null");
        }
        if (inventoryGroup.isEmpty()) {
            if (inventoryInterval != null) {
                throw new IllegalArgumentException(
                        "inventoryInterval requires a configured inventory group");
            }
        } else {
            if (inventoryGroup.size() < 2) {
                throw new IllegalArgumentException(
                        "inventory group requires at least two antennas");
            }
            if (inventoryInterval == null
                    || inventoryInterval.isZero()
                    || inventoryInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "inventoryInterval must be positive");
            }
        }

        this.inventoryGroup =
                Collections.unmodifiableList(
                        new ArrayList<ManagedAntenna>(
                                inventoryGroup));
        this.inventoryInterval =
                inventoryInterval;
    }

    @Override
    public boolean hasInventoryGroup() {
        return !inventoryGroup.isEmpty();
    }

    @Override
    public Duration inventoryInterval() {
        if (inventoryInterval == null) {
            throw new IllegalStateException(
                    "no inventory group is configured");
        }
        return inventoryInterval;
    }

    /**
     * Returns whether periodic round-robin switching is useful.
     */
    @Override
    public boolean rotationNeeded() {
        return availableCount() > 1;
    }

    /**
     * Starts one available group member when none is inventorying.
     */
    @Override
    public boolean startFirstAvailable() {
        if (currentInventoryIndex() >= 0) {
            return true;
        }

        for (ManagedAntenna antenna : inventoryGroup) {
            if (antenna.startInventory()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rotates from the current member to the next available member.
     *
     * <p>If stopping the current member fails, no next member is started. This
     * preserves the at-most-one-inventory invariant even when the failed reader
     * may still be inventorying.</p>
     */
    @Override
    public void rotateInventoryGroup() {
        if (inventoryGroup.isEmpty()) {
            return;
        }

        int currentIndex =
                currentInventoryIndex();

        if (currentIndex < 0) {
            startFirstAvailable();
            return;
        }

        if (!rotationNeeded()) {
            return;
        }

        ManagedAntenna current =
                inventoryGroup.get(
                        currentIndex);
        if (!current.stopInventory()) {
            return;
        }

        int firstCandidate =
                currentIndex + 1;

        for (int offset = 0;
                offset < inventoryGroup.size();
                offset++) {
            ManagedAntenna candidate =
                    inventoryGroup.get(
                            (firstCandidate + offset)
                                    % inventoryGroup.size());
            if (candidate.startInventory()) {
                return;
            }
        }
    }

    private int currentInventoryIndex() {
        for (int index = 0;
                index < inventoryGroup.size();
                index++) {
            if (inventoryGroup.get(index)
                    .inventoryRunning()) {
                return index;
            }
        }
        return -1;
    }

    private int availableCount() {
        int count = 0;
        for (ManagedAntenna antenna : inventoryGroup) {
            if (antenna.availableForInventory()) {
                count++;
            }
        }
        return count;
    }
}
