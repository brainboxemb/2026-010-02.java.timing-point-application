package oss.relay.timing.timingpoint.runtime.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered AntennaManager configuration bindings keyed by TimingSystem id. */
final class AntennaManagerConfigRegistry {
    private final Map<String, AntennaManagerConfig> bindings =
            new LinkedHashMap<String, AntennaManagerConfig>();

    AntennaManagerConfigRegistry(
            List<AntennaManagerConfig> antennaManagers,
            TimingSystemConfigRegistry timingSystems) {
        if (antennaManagers == null) {
            throw new IllegalArgumentException(
                    "antennaManagers must not be null");
        }
        if (timingSystems == null) {
            throw new IllegalArgumentException(
                    "timingSystems must not be null");
        }

        for (AntennaManagerConfig binding
                : antennaManagers) {
            register(
                    binding,
                    timingSystems);
        }
    }

    private void register(
            AntennaManagerConfig binding,
            TimingSystemConfigRegistry timingSystems) {
        if (binding == null
                || !timingSystems.containsSystem(
                        binding.timingSystemId())
                || bindings.containsKey(
                        binding.timingSystemId())) {
            throw new IllegalArgumentException(
                    "Unknown or duplicate AntennaManager TimingSystem binding");
        }

        bindings.put(
                binding.timingSystemId(),
                binding);
    }

    List<AntennaManagerConfig> bindings() {
        return Collections.unmodifiableList(
                new ArrayList<AntennaManagerConfig>(
                        bindings.values()));
    }

    AntennaManagerConfig binding(
            String timingSystemId) {
        return bindings.get(
                timingSystemId);
    }
}
