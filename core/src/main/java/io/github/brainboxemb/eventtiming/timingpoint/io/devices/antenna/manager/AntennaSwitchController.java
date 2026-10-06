package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.task.AntennaTasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Selects the current/next member of one mutual-exclusion inventory group.
 *
 * <p>This object owns no scheduler and performs no multi-step sequence. The
 * cooperative AntennaSwitchTask calls stop and start as separate turns.</p>
 */
final class AntennaSwitchController implements AntennaTasks.SwitchTarget {

    private final List<ManagedAntenna> inventoryGroup;
    private final Duration inventoryInterval;

    private int nextIndex;

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

    @Override
    public boolean rotationNeeded() {
        return availableCount() > 1;
    }

    @Override
    public boolean startFirstAvailable() {
        if (currentInventoryIndex() >= 0) {
            return true;
        }

        nextIndex = 0;
        return startNextAvailable();
    }

    @Override
    public boolean stopCurrent() {
        int currentIndex =
                currentInventoryIndex();

        if (currentIndex < 0) {
            return true;
        }

        ManagedAntenna current =
                inventoryGroup.get(
                        currentIndex);
        nextIndex =
                (currentIndex + 1)
                        % inventoryGroup.size();

        try {
            current.stopInventory();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public boolean startNextAvailable() {
        if (inventoryGroup.isEmpty()) {
            return false;
        }
        if (currentInventoryIndex() >= 0) {
            return true;
        }

        for (int offset = 0;
                offset < inventoryGroup.size();
                offset++) {
            int candidateIndex =
                    (nextIndex + offset)
                            % inventoryGroup.size();
            ManagedAntenna candidate =
                    inventoryGroup.get(
                            candidateIndex);

            if (!candidate.availableForInventory()) {
                continue;
            }

            try {
                candidate.startInventory();
                nextIndex =
                        (candidateIndex + 1)
                                % inventoryGroup.size();
                return true;
            } catch (RuntimeException ex) {
                // Failure is stored on the candidate; try the next available.
            }
        }

        return false;
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
