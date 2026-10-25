package oss.relay.eventtiming.timingpoint.runtime.config;

import oss.relay.eventtiming.timingpoint.io.devices.antenna.AntennaId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** IF-11 antenna binding owned by exactly one configured TimingSystem. */
public final class AntennaManagerConfig {
    /** One configured antenna. */
    public static final class AntennaConfig {
        private final AntennaId id;
        private final String providerId;

        public AntennaConfig(
                AntennaId id,
                String providerId) {
            if (id == null) {
                throw new IllegalArgumentException("AntennaId must not be null");
            }
            if (providerId == null || providerId.trim().isEmpty()) {
                throw new IllegalArgumentException("Antenna provider must not be blank");
            }
            this.id = id;
            this.providerId = providerId.trim();
        }

        public AntennaId id() {
            return id;
        }

        public String providerId() {
            return providerId;
        }

    }

    private final String timingSystemId;
    private final List<AntennaConfig> antennas;
    private final List<AntennaId> inventoryGroup;
    private final Duration inventoryInterval;

    public AntennaManagerConfig(
            String timingSystemId,
            List<AntennaConfig> antennas,
            List<AntennaId> inventoryGroup,
            Duration inventoryInterval) {
        if (timingSystemId == null || timingSystemId.trim().isEmpty()) {
            throw new IllegalArgumentException("AntennaManager timingSystemId must not be blank");
        }
        if (antennas == null || antennas.isEmpty()) {
            throw new IllegalArgumentException("AntennaManager must contain antennas");
        }
        Set<AntennaId> ids = new LinkedHashSet<AntennaId>();
        for (AntennaConfig antenna : antennas) {
            if (antenna == null || !ids.add(antenna.id())) {
                throw new IllegalArgumentException("Duplicate or null AntennaId in AntennaManager");
            }
        }

        List<AntennaId> group = inventoryGroup == null
                ? Collections.<AntennaId>emptyList() : inventoryGroup;
        if (group.isEmpty()) {
            if (inventoryInterval != null) {
                throw new IllegalArgumentException("Inventory interval requires an inventoryGroup");
            }
        } else {
            if (group.size() < 2 || inventoryInterval == null
                    || inventoryInterval.isZero() || inventoryInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "inventoryGroup requires at least two antennas and a positive interval");
            }
            Set<AntennaId> members = new LinkedHashSet<AntennaId>();
            for (AntennaId id : group) {
                if (id == null || !ids.contains(id) || !members.add(id)) {
                    throw new IllegalArgumentException(
                            "Unknown or duplicate inventoryGroup antenna " + id);
                }
            }
        }

        this.timingSystemId = timingSystemId.trim();
        this.antennas = Collections.unmodifiableList(new ArrayList<AntennaConfig>(antennas));
        this.inventoryGroup = Collections.unmodifiableList(new ArrayList<AntennaId>(group));
        this.inventoryInterval = inventoryInterval;
    }

    public String timingSystemId() {
        return timingSystemId;
    }

    public List<AntennaConfig> antennas() {
        return antennas;
    }

    public List<AntennaId> inventoryGroup() {
        return inventoryGroup;
    }

    public Duration inventoryInterval() {
        return inventoryInterval;
    }
}
