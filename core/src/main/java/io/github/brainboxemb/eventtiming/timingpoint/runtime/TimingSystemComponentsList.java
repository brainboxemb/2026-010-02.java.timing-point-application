package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered Runtime component groups for distinct configured TimingSystems. */
final class TimingSystemComponentsList
        implements Iterable<TimingSystemComponents> {
    private final List<TimingSystemComponents> systems =
            new ArrayList<TimingSystemComponents>();
    private final Set<String> ids =
            new LinkedHashSet<String>();

    TimingSystemComponentsList add(
            TimingSystemComponents system) {
        if (system == null) {
            throw new IllegalArgumentException(
                    "TimingSystem components must not be null");
        }
        if (!ids.add(system.id())) {
            throw new IllegalArgumentException(
                    "Duplicate TimingSystem id " + system.id());
        }
        systems.add(system);
        return this;
    }

    int size() {
        return systems.size();
    }

    TimingSystemComponentsList copy() {
        TimingSystemComponentsList copy =
                new TimingSystemComponentsList();
        for (TimingSystemComponents system : systems) {
            copy.add(system);
        }
        return copy;
    }

    List<AntennaManager> antennaManagers() {
        List<AntennaManager> managers =
                new ArrayList<AntennaManager>();
        for (TimingSystemComponents system : systems) {
            if (system.antennaManager() != null) {
                managers.add(system.antennaManager());
            }
        }
        return Collections.unmodifiableList(managers);
    }

    @Override
    public Iterator<TimingSystemComponents> iterator() {
        return Collections.unmodifiableList(systems).iterator();
    }
}
