package oss.relay.eventtiming.timingpoint.runtime.config;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.runtime.config.YamlTopologyMapper.TimingNodeStartup;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps TimingData storage bindings from the IO YAML section. */
final class YamlTimingDataStorageMapper {
    private static final String IO = "io";
    private static final String STORAGE = "storage";
    private static final String DEVICES = "devices";
    private static final String TIMING_DATA = "timingData";
    private static final String PATH = "path";
    private static final String STORAGE_NODES = "nodes";
    private static final String NODE_ID = "nodeId";

    private YamlTimingDataStorageMapper() {
    }

    /** Resolves a unique TimingData storage path for every configured TimingNode. */
    static Map<NodeId, Path> map(
            Object rawIo,
            List<TimingNodeStartup> timingNodes) {
        if (rawIo == null) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + IO);
        }

        Map<?, ?> io =
                YamlValues.requireMapping(
                        rawIo,
                        IO);
        YamlValues.rejectUnknownFields(
                io,
                IO,
                STORAGE,
                DEVICES);
        if (!io.containsKey(STORAGE)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + IO
                            + "."
                            + STORAGE);
        }

        String storageField =
                IO
                        + "."
                        + STORAGE;
        Map<?, ?> storage =
                YamlValues.requireMapping(
                        io.get(
                                STORAGE),
                        storageField);
        YamlValues.rejectUnknownFields(
                storage,
                storageField,
                TIMING_DATA);
        if (!storage.containsKey(TIMING_DATA)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + storageField
                            + "."
                            + TIMING_DATA);
        }

        String timingDataField =
                storageField
                        + "."
                        + TIMING_DATA;
        Map<?, ?> timingData =
                YamlValues.requireMapping(
                        storage.get(
                                TIMING_DATA),
                        timingDataField);
        YamlValues.rejectUnknownFields(
                timingData,
                timingDataField,
                PATH,
                STORAGE_NODES);

        boolean hasPath =
                timingData.containsKey(
                        PATH);
        boolean hasNodes =
                timingData.containsKey(
                        STORAGE_NODES);
        if (hasPath == hasNodes) {
            throw new IllegalArgumentException(
                    timingDataField
                            + " must define exactly one of path or nodes");
        }

        if (hasPath) {
            return mapTemplatePaths(
                    timingData,
                    timingDataField,
                    timingNodes);
        }

        return mapNodePaths(
                timingData,
                timingDataField,
                timingNodes);
    }

    /** Expands one shared contextual path template for all configured nodes. */
    private static Map<NodeId, Path> mapTemplatePaths(
            Map<?, ?> timingData,
            String timingDataField,
            List<TimingNodeStartup> timingNodes) {
        String field =
                timingDataField
                        + "."
                        + PATH;
        String template =
                mapPathTemplate(
                        timingData.get(
                                PATH),
                        field);
        if (timingNodes.size() != 1
                && !YamlTemplateResolver
                        .hasContextPlaceholder(
                                template)) {
            throw new IllegalArgumentException(
                    field
                            + " without {NodeId} or {SystemId} is only valid for exactly one TimingNode");
        }

        Map<NodeId, Path> result =
                new LinkedHashMap<NodeId, Path>();
        Set<Path> normalizedPaths =
                new LinkedHashSet<Path>();
        for (TimingNodeStartup timingNode : timingNodes) {
            addTimingDataPath(
                    result,
                    normalizedPaths,
                    timingNode.timingNodeId(),
                    mapPath(
                            template,
                            field,
                            timingNode));
        }
        return result;
    }

    /** Maps explicit per-node TimingData storage bindings. */
    private static Map<NodeId, Path> mapNodePaths(
            Map<?, ?> timingData,
            String timingDataField,
            List<TimingNodeStartup> timingNodes) {
        String nodesField =
                timingDataField
                        + "."
                        + STORAGE_NODES;
        Map<?, ?> bindings =
                YamlValues.requireMapping(
                        timingData.get(
                                STORAGE_NODES),
                        nodesField);
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    nodesField
                            + " must contain at least one storage binding");
        }

        Map<NodeId, TimingNodeStartup> configuredNodes =
                new LinkedHashMap<NodeId, TimingNodeStartup>();
        for (TimingNodeStartup timingNode : timingNodes) {
            configuredNodes.put(
                    timingNode.timingNodeId(),
                    timingNode);
        }

        Map<NodeId, Path> result =
                new LinkedHashMap<NodeId, Path>();
        Set<Path> normalizedPaths =
                new LinkedHashSet<Path>();

        for (Map.Entry<?, ?> entry : bindings.entrySet()) {
            String bindingName =
                    requireMappingEntryName(
                            entry.getKey(),
                            nodesField);
            String bindingField =
                    nodesField
                            + "."
                            + bindingName;
            Map<?, ?> binding =
                    YamlValues.requireMapping(
                            entry.getValue(),
                            bindingField);
            YamlValues.rejectUnknownFields(
                    binding,
                    bindingField,
                    NODE_ID,
                    PATH);

            NodeId nodeId =
                    new NodeId(
                            YamlValues.requireString(
                                    binding.get(
                                            NODE_ID),
                                    bindingField
                                            + "."
                                            + NODE_ID));
            TimingNodeStartup configuredNode =
                    configuredNodes.get(
                            nodeId);
            if (configuredNode == null) {
                throw new IllegalArgumentException(
                        bindingField
                                + "."
                                + NODE_ID
                                + " references unknown TimingNode "
                                + nodeId.value());
            }
            if (result.containsKey(nodeId)) {
                throw new IllegalArgumentException(
                        "Duplicate TimingData storage binding for TimingNode "
                                + nodeId.value());
            }

            String field =
                    bindingField
                            + "."
                            + PATH;
            addTimingDataPath(
                    result,
                    normalizedPaths,
                    nodeId,
                    mapPath(
                            mapPathTemplate(
                                    binding.get(
                                            PATH),
                                    field),
                            field,
                            configuredNode));
        }

        if (result.size() != configuredNodes.size()) {
            for (NodeId nodeId : configuredNodes.keySet()) {
                if (!result.containsKey(nodeId)) {
                    throw new IllegalArgumentException(
                            "Missing TimingData storage binding for TimingNode "
                                    + nodeId.value());
                }
            }
        }

        return result;
    }

    /** Requires a non-blank mapping entry name used only for diagnostics. */
    private static String requireMappingEntryName(
            Object rawKey,
            String field) {
        if (!(rawKey instanceof String)
                || ((String) rawKey)
                        .trim()
                        .isEmpty()) {
            throw new IllegalArgumentException(
                    field
                            + " entry name must be a non-blank YAML string");
        }
        return ((String) rawKey).trim();
    }

    /** Reads and validates one raw TimingData path template. */
    private static String mapPathTemplate(
            Object rawPath,
            String field) {
        String value =
                YamlValues.requireTemplateString(
                        rawPath,
                        field)
                        .trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    field
                            + " must not be blank");
        }
        return value;
    }

    /** Resolves contextual node/system placeholders and validates the filesystem path. */
    private static Path mapPath(
            String template,
            String field,
            TimingNodeStartup timingNode) {
        String value =
                YamlTemplateResolver.resolveContext(
                        template,
                        field,
                        timingNode
                                .timingNodeId()
                                .value(),
                        timingNode
                                .timingSystemId());

        try {
            return Paths.get(
                    value);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException(
                    field
                            + " is not a valid filesystem path",
                    ex);
        }
    }

    /** Adds one node path while enforcing application-wide storage-path uniqueness. */
    private static void addTimingDataPath(
            Map<NodeId, Path> result,
            Set<Path> normalizedPaths,
            NodeId nodeId,
            Path path) {
        Path normalized =
                path
                        .toAbsolutePath()
                        .normalize();
        if (!normalizedPaths.add(normalized)) {
            throw new IllegalArgumentException(
                    "Duplicate TimingData storage path "
                            + path);
        }
        result.put(
                nodeId,
                path);
    }
}
