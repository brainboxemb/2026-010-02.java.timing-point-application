package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Coordinates switching across the configured antennas.
 *
 * <p>Single-antenna power/provider behaviour lives in {@link ManagedAntenna}.
 * This class only coordinates the set: validation, status lookup, enabling all
 * independent antennas and rotating the optional mutual-exclusion group.</p>
 *
 * <p>The controller does not own a worker or scheduler. For multi-step power
 * stabilization sequences it receives the manager-owned control lane as an
 * execution capability, while AntennaManager remains lifecycle owner.</p>
 */
final class AntennaSwitchController {

    private final List<ManagedAntenna> antennas;
    private final List<ManagedAntenna> inventoryGroup;
    private final Duration inventoryInterval;

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

            ManagedAntenna antenna =
                    new ManagedAntenna(
                            installation);
            configured.add(antenna);

            if (antenna.inInventoryGroup()) {
                groupInterval =
                        sharedGroupInterval(
                                groupInterval,
                                antenna.inventoryInterval());
                group.add(antenna);
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
        return find(antennaId)
                .status();
    }

    Throwable failure() {
        for (ManagedAntenna antenna : antennas) {
            if (antenna.failure() != null) {
                return antenna.failure();
            }
        }
        return null;
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
     * Probes every configured antenna once without occupying the physical worker
     * while external power stabilization time elapses.
     */
    CompletableFuture<Void> probeAll(
            AntennaControlLane control) {
        CompletableFuture<Void> sequence =
                CompletableFuture.completedFuture(null);

        for (ManagedAntenna antenna : antennas) {
            sequence =
                    sequence.thenCompose(
                            ignored ->
                                    control.runDelayed(
                                            antenna::beginProbe,
                                            antenna::completeProbe));
        }
        return sequence;
    }

    /**
     * Enables all independent antennas and one member of the multiplex group.
     *
     * <p>The supplied guard lets AntennaManager invalidate an older enable
     * transition when a newer inventory request arrives while stabilization is
     * still pending.</p>
     */
    CompletableFuture<Void> enableInventory(
            AntennaControlLane control,
            BooleanSupplier stillCurrent) {
        CompletableFuture<Void> sequence =
                CompletableFuture.completedFuture(null);

        for (ManagedAntenna antenna : antennas) {
            if (antenna.inInventoryGroup()) {
                continue;
            }

            sequence =
                    sequence.thenCompose(
                            ignored -> {
                                if (!stillCurrent.getAsBoolean()) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                return control.runDelayed(
                                        () -> stillCurrent.getAsBoolean()
                                                ? antenna.beginPrepareForInventory()
                                                : null,
                                        () -> {
                                            if (stillCurrent.getAsBoolean()
                                                    && antenna.completePrepareForInventory()) {
                                                antenna.startInventory();
                                            }
                                        });
                            });
        }

        for (ManagedAntenna antenna : inventoryGroup) {
            sequence =
                    sequence.thenCompose(
                            ignored -> {
                                if (!stillCurrent.getAsBoolean()) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                return control.runDelayed(
                                        () -> stillCurrent.getAsBoolean()
                                                ? antenna.beginPrepareForInventory()
                                                : null,
                                        () -> {
                                            if (stillCurrent.getAsBoolean()) {
                                                antenna.completePrepareForInventory();
                                            }
                                        });
                            });
        }

        if (!inventoryGroup.isEmpty()) {
            sequence =
                    sequence.thenCompose(
                            ignored -> {
                                if (!stillCurrent.getAsBoolean()) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                return control.runAsync(
                                        () -> {
                                            if (stillCurrent.getAsBoolean()) {
                                                startFirstAvailable(
                                                        inventoryGroup);
                                            }
                                        });
                            });
        }

        return sequence;
    }

    /** Stops inventory and removes power from every configured antenna. */
    void disableInventory() {
        for (int index = antennas.size() - 1;
                index >= 0;
                index--) {
            antennas.get(index)
                    .disableInventory();
        }
    }

    /**
     * Rotates inventory to the next healthy member of the optional group.
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
            inventoryGroup.get(currentIndex)
                    .stopInventory();
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
            if (candidate.startInventory()) {
                return;
            }
        }
    }

    /** Closes all physical antennas in reverse configuration order. */
    void closeAll() {
        RuntimeException firstFailure = null;

        for (int index = antennas.size() - 1;
                index >= 0;
                index--) {
            try {
                antennas.get(index)
                        .close();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    State aggregateState() {
        int healthy = 0;
        int failed = 0;

        for (ManagedAntenna antenna : antennas) {
            if (antenna.failure() != null) {
                failed++;
            } else if (antenna.healthy()) {
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

    private void startFirstAvailable(
            List<ManagedAntenna> candidates) {
        for (ManagedAntenna antenna : candidates) {
            if (antenna.inventoryRunning()) {
                return;
            }
        }

        for (ManagedAntenna antenna : candidates) {
            if (antenna.startInventory()) {
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

    private ManagedAntenna find(
            AntennaId antennaId) {
        if (antennaId == null) {
            throw new IllegalArgumentException(
                    "antennaId must not be null");
        }

        for (ManagedAntenna antenna : antennas) {
            if (antenna.antennaId()
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
            if (antenna.healthy()) {
                count++;
            }
        }
        return count;
    }
}
