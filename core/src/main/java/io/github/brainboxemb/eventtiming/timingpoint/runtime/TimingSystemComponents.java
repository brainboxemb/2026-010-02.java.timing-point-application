package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Runtime-only grouping of constructed components for one configured system.
 * This is neither a Domain TimingSystem nor an I/O facade passed into Domain.
 */
final class TimingSystemComponents {
    private final String id;
    private final TimingNodeList nodes;
    private final AntennaManager antennaManager;

    /** Creates one runtime-only group for a validated configured TimingSystem. */
    TimingSystemComponents(String id, TimingNodeList nodes, AntennaManager antennaManager) {
        checkArgument(id != null && !id.trim().isEmpty(), "TimingSystem id must not be blank");
        checkArgument(nodes != null && !nodes.isEmpty(), "TimingSystem nodes must not be empty");
        this.id = id.trim();
        this.nodes = nodes.copy();
        this.antennaManager = antennaManager;
    }

    /** Returns the configured TimingSystem identity. */
    String id() {
        return id;
    }

    /** Returns the composed TimingNodes for this runtime group. */
    TimingNodeList nodes() {
        return nodes;
    }

    /** Returns the optional AntennaManager composed for this system. */
    AntennaManager antennaManager() {
        return antennaManager;
    }
}
