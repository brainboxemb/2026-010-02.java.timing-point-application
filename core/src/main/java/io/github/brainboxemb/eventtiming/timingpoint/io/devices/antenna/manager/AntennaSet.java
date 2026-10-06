package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaStatus;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.power.PowerDevice;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * The antennas owned by one {@link AntennaManager}.
 *
 * <p>This is a composition-time collection. It binds an {@link AntennaId} to an
 * antenna device and, when needed, to its external power device. Multiplexing is
 * configured separately for the set, so group membership is not hidden inside
 * an individual antenna value.</p>
 *
 * <p>The set becomes immutable when it is given to an AntennaManager.</p>
 */
public final class AntennaSet {

    private final List<ManagedAntenna> antennas = new ArrayList<ManagedAntenna>();
    private final List<ManagedAntenna> inventoryGroup = new ArrayList<ManagedAntenna>();

    private Duration inventoryInterval;
    private boolean sealed;

    /** Adds an antenna whose power lifecycle is handled by the antenna itself. */
    public AntennaSet add(AntennaId antennaId, Antenna antenna) {
        return add(antennaId, antenna, null, Duration.ZERO);
    }

    /** Adds an antenna controlled through a separate external power device. */
    public AntennaSet addPowered(
            AntennaId antennaId,
            Antenna antenna,
            PowerDevice powerDevice,
            Duration stabilization) {
        checkArgument(powerDevice != null, "powerDevice must not be null");
        return add(antennaId, antenna, powerDevice, stabilization);
    }

    /**
     * Configures the optional group whose members must not inventory concurrently.
     *
     * <p>All members are prepared before the inventory task starts rotating
     * between them.</p>
     */
    public AntennaSet inventoryGroup(Duration interval, AntennaId... members) {
        checkMutable();
        checkArgument(interval != null && !interval.isZero() && !interval.isNegative(),
                "inventory interval must be positive");
        checkArgument(members != null && members.length >= 2,
                "inventory group requires at least two antennas");

        inventoryGroup.clear();
        for (AntennaId member : members) {
            ManagedAntenna antenna = find(member);
            checkArgument(!inventoryGroup.contains(antenna), "duplicate inventory-group member %s", member);
            inventoryGroup.add(antenna);
        }

        inventoryInterval = interval;
        return this;
    }

    /** Returns configured antenna IDs in composition order. */
    public List<AntennaId> antennaIds() {
        List<AntennaId> result = new ArrayList<AntennaId>(antennas.size());
        for (ManagedAntenna antenna : antennas) {
            result.add(antenna.antennaId());
        }
        return Collections.unmodifiableList(result);
    }

    List<ManagedAntenna> antennas() {
        return Collections.unmodifiableList(antennas);
    }

    List<ManagedAntenna> inventoryGroup() {
        return Collections.unmodifiableList(inventoryGroup);
    }

    boolean hasInventoryGroup() {
        return !inventoryGroup.isEmpty();
    }

    Duration inventoryInterval() {
        checkState(inventoryInterval != null, "no inventory group is configured");
        return inventoryInterval;
    }

    public boolean isEmpty() {
        return antennas.isEmpty();
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

    EventSource<TagObservation> tagObservedEvent(AntennaId antennaId) {
        return find(antennaId).tagObservedEvent();
    }

    List<AntennaStatus> statuses() {
        List<AntennaStatus> result = new ArrayList<AntennaStatus>(antennas.size());
        for (ManagedAntenna antenna : antennas) {
            result.add(antenna.status());
        }
        return Collections.unmodifiableList(result);
    }

    AntennaStatus status(AntennaId antennaId) {
        return find(antennaId).status();
    }

    void seal() {
        checkArgument(!antennas.isEmpty(), "antenna set must contain at least one antenna");
        sealed = true;
    }

    private AntennaSet add(
            AntennaId antennaId,
            Antenna antenna,
            PowerDevice powerDevice,
            Duration stabilization) {
        checkMutable();
        checkArgument(antennaId != null, "antennaId must not be null");
        checkArgument(antenna != null, "antenna must not be null");
        checkArgument(stabilization != null && !stabilization.isNegative(),
                "power stabilization must not be negative");
        checkArgument(powerDevice != null || stabilization.isZero(),
                "power stabilization requires a power device");
        checkArgument(findOrNull(antennaId) == null, "duplicate AntennaId %s", antennaId);

        antennas.add(new ManagedAntenna(antennaId, antenna, powerDevice, stabilization));
        return this;
    }

    private ManagedAntenna find(AntennaId antennaId) {
        checkArgument(antennaId != null, "antennaId must not be null");

        ManagedAntenna antenna = findOrNull(antennaId);
        if (antenna == null) {
            throw new IllegalArgumentException("unknown AntennaId " + antennaId);
        }
        return antenna;
    }

    private ManagedAntenna findOrNull(AntennaId antennaId) {
        for (ManagedAntenna antenna : antennas) {
            if (antenna.antennaId().equals(antennaId)) {
                return antenna;
            }
        }
        return null;
    }

    private void checkMutable() {
        checkState(!sealed, "AntennaSet is already owned by AntennaManager");
    }
}
