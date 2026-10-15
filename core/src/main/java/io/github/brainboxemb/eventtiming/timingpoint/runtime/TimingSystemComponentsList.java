package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/** Ordered Runtime component groups for distinct configured TimingSystems. */
final class TimingSystemComponentsList implements Iterable<TimingSystemComponents> {
    private final List<TimingSystemComponents> systems = new ArrayList<TimingSystemComponents>();
    private final Set<String> ids = new LinkedHashSet<String>();

    /** Adds one runtime component group with a distinct TimingSystem identity. */
    TimingSystemComponentsList add(TimingSystemComponents system) {
        checkArgument(system != null, "TimingSystem components must not be null");
        checkArgument(!ids.contains(system.id()), "Duplicate TimingSystem id %s", system.id());

        ids.add(system.id());
        systems.add(system);
        return this;
    }

    /** Returns the number of configured runtime system groups. */
    int size() {
        return systems.size();
    }

    /** Returns an independent ordered list containing the same component groups. */
    TimingSystemComponentsList copy() {
        TimingSystemComponentsList copy = new TimingSystemComponentsList();
        for (TimingSystemComponents system : systems) {
            copy.add(system);
        }
        return copy;
    }

    /** Returns the immutable ordered list of composed AntennaManagers. */
    List<AntennaManager> antennaManagers() {
        List<AntennaManager> managers = new ArrayList<AntennaManager>();
        for (TimingSystemComponents system : systems) {
            if (system.antennaManager() != null) {
                managers.add(system.antennaManager());
            }
        }
        return Collections.unmodifiableList(managers);
    }

    /** Iterates over configured system groups without exposing list mutation. */
    @Override
    public Iterator<TimingSystemComponents> iterator() {
        return Collections.unmodifiableList(systems).iterator();
    }
}
