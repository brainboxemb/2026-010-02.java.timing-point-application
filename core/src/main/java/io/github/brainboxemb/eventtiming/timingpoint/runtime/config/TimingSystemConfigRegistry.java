package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ordered registry of effective TimingSystem configuration.
 *
 * <p>The registry owns application-wide TimingSystem and TimingNode identity
 * validation plus unique TimingData storage paths. It does not own YAML
 * mapping, provider selection or runtime composition.</p>
 */
final class TimingSystemConfigRegistry {
    private final Map<String, Config.TimingSystemConfig> systems =
            new LinkedHashMap<String, Config.TimingSystemConfig>();
    private final Map<NodeId, Config.TimingNodeConfig> nodes =
            new LinkedHashMap<NodeId, Config.TimingNodeConfig>();
    private final Set<Path> storagePaths =
            new LinkedHashSet<Path>();

    TimingSystemConfigRegistry(
            List<Config.TimingSystemConfig> timingSystems) {
        if (timingSystems == null
                || timingSystems.isEmpty()) {
            throw new IllegalArgumentException(
                    "timingSystems must contain at least one TimingSystem");
        }

        for (Config.TimingSystemConfig timingSystem
                : timingSystems) {
            register(timingSystem);
        }

        // Multi-node system IDs occupy their own globally unique slot.
        // They must not be confused with *any* TimingNode in this application.
        for (Config.TimingSystemConfig timingSystem : timingSystems) {
            if (timingSystem.timingNodes().size() > 1
                    && nodes.containsKey(new NodeId(timingSystem.timingSystemId()))) {
                throw new IllegalArgumentException(
                        "Multi-node TimingSystemId "
                                + timingSystem.timingSystemId()
                                + " must differ from all TimingNodeIds");
            }
        }
    }

    private void register(
            Config.TimingSystemConfig timingSystem) {
        if (timingSystem == null) {
            throw new IllegalArgumentException(
                    "timingSystems must not contain null");
        }

        String systemId =
                timingSystem.timingSystemId();
        if (systems.containsKey(systemId)) {
            throw new IllegalArgumentException(
                    "Duplicate TimingSystem id "
                            + systemId);
        }

        for (Config.TimingNodeConfig timingNode
                : timingSystem.timingNodes()) {
            NodeId nodeId =
                    timingNode.timingNodeId();
            if (nodes.containsKey(nodeId)) {
                throw new IllegalArgumentException(
                        "Duplicate application-wide TimingNode id "
                                + nodeId.value());
            }

            Path storagePath =
                    timingNode.timingDataPath();
            if (storagePath != null) {
                Path normalized =
                        storagePath
                                .toAbsolutePath()
                                .normalize();
                if (!storagePaths.add(normalized)) {
                    throw new IllegalArgumentException(
                            "Duplicate TimingData storage path "
                                    + storagePath);
                }
            }
        }

        systems.put(systemId, timingSystem);
        for (Config.TimingNodeConfig timingNode
                : timingSystem.timingNodes()) {
            nodes.put(
                    timingNode.timingNodeId(),
                    timingNode);
        }
    }

    boolean containsSystem(
            String timingSystemId) {
        return timingSystemId != null
                && systems.containsKey(
                        timingSystemId);
    }

    Config.TimingSystemConfig system(
            String timingSystemId) {
        if (timingSystemId == null) {
            throw new IllegalArgumentException(
                    "timingSystemId must not be null");
        }

        Config.TimingSystemConfig timingSystem =
                systems.get(timingSystemId);
        if (timingSystem == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingSystem configuration "
                            + timingSystemId);
        }
        return timingSystem;
    }

    Config.TimingNodeConfig timingNode(
            NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException(
                    "nodeId must not be null");
        }

        Config.TimingNodeConfig timingNode =
                nodes.get(nodeId);
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingNode configuration "
                            + nodeId.value());
        }
        return timingNode;
    }

    List<Config.TimingSystemConfig> systems() {
        return Collections.unmodifiableList(
                new ArrayList<Config.TimingSystemConfig>(
                        systems.values()));
    }

    List<Config.TimingNodeConfig> timingNodes() {
        return Collections.unmodifiableList(
                new ArrayList<Config.TimingNodeConfig>(
                        nodes.values()));
    }

    Set<NodeId> timingNodeIds(
            String timingSystemId) {
        Config.TimingSystemConfig timingSystem =
                system(timingSystemId);
        Set<NodeId> result =
                new LinkedHashSet<NodeId>();
        for (Config.TimingNodeConfig timingNode
                : timingSystem.timingNodes()) {
            result.add(
                    timingNode.timingNodeId());
        }
        return Collections.unmodifiableSet(result);
    }
}
