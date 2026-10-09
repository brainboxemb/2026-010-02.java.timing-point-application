package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ordered provider-resolved TimingSystem composition data keyed by system id. */
final class TimingSystemResolvedDataList
        implements Iterable<TimingSystemResolvedData> {
    private final List<TimingSystemResolvedData> systems =
            new ArrayList<TimingSystemResolvedData>();
    private final Set<String> ids =
            new LinkedHashSet<String>();

    TimingSystemResolvedDataList add(
            TimingSystemResolvedData system) {
        if (system == null) {
            throw new IllegalArgumentException(
                    "TimingSystem resolved data must not be null");
        }
        if (!ids.add(system.id())) {
            throw new IllegalArgumentException(
                    "Duplicate TimingSystem id " + system.id());
        }
        systems.add(system);
        return this;
    }

    boolean isEmpty() {
        return systems.isEmpty();
    }

    int size() {
        return systems.size();
    }

    TimingSystemResolvedData get(int index) {
        return systems.get(index);
    }

    @Override
    public Iterator<TimingSystemResolvedData> iterator() {
        return Collections.unmodifiableList(systems).iterator();
    }
}
