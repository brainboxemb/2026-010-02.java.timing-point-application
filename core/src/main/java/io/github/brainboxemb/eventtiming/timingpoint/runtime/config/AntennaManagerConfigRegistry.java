package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        Set<NodeId> systemNodes =
                timingSystems.timingNodeIds(
                        binding.timingSystemId());
        for (AntennaManagerConfig.AntennaConfig antenna
                : binding.antennas()) {
            for (NodeId nodeId
                    : antenna.timingNodes()) {
                if (!systemNodes.contains(nodeId)) {
                    throw new IllegalArgumentException(
                            "Antenna "
                                    + antenna.id()
                                    + " routes to a TimingNode outside TimingSystem "
                                    + binding.timingSystemId()
                                    + ": "
                                    + nodeId.value());
                }
            }
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
