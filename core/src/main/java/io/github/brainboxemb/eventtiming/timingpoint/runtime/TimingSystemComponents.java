package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

/**
 * Runtime-only grouping of constructed components for one configured system.
 * This is neither a Domain TimingSystem nor an I/O facade passed into Domain.
 */
final class TimingSystemComponents {
    private final String id;
    private final TimingNodeList nodes;
    private final AntennaManager antennaManager;

    TimingSystemComponents(
            String id,
            TimingNodeList nodes,
            AntennaManager antennaManager) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "TimingSystem id must not be blank");
        }
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "TimingSystem nodes must not be empty");
        }
        this.id = id.trim();
        this.nodes = nodes.copy();
        this.antennaManager = antennaManager;
    }

    String id() {
        return id;
    }

    TimingNodeList nodes() {
        return nodes;
    }

    AntennaManager antennaManager() {
        return antennaManager;
    }
}
