package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Runtime-only ownership of the nodes and I/O in one configured TimingSystem. */
final class TimingSystemComponents {
    private final String id;
    private final List<TimingNode> nodes;
    private final AntennaManager antennaManager;

    TimingSystemComponents(String id, List<TimingNode> nodes, AntennaManager antennaManager) {
        this.id = id;
        this.nodes = Collections.unmodifiableList(new ArrayList<TimingNode>(nodes));
        this.antennaManager = antennaManager;
    }

    String id() {
        return id;
    }

    List<TimingNode> nodes() {
        return nodes;
    }

    AntennaManager antennaManager() {
        return antennaManager;
    }
}
