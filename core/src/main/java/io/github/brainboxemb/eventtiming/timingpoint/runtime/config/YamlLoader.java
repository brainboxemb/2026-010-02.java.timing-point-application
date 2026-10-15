package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Maps the default IF-11 YAML syntax into the runtime configuration model.
 *
 * <p>This loader belongs with the runtime configuration because it knows the
 * concrete application configuration schema. It is not a generic infrastructure
 * YAML utility.</p>
 */
public final class YamlLoader {
    private static final String TIMING_SYSTEMS = "timingSystems";
    // Source YAML uses compact but typed identifiers on every object.
    private static final String SYSTEM_ID = "systemId";
    private static final String EVENT_DATA_PROVIDER = "eventDataProvider";
    private static final String TIMING_DATA_PROVIDER = "timingDataProvider";
    private static final String TIMING_NODES = "timingNodes";
    private static final String NODE_ID = "nodeId";
    private static final String TAG_PROCESSING = "tagProcessing";
    private static final String QUIET_TIMEOUT_MILLIS = "quietTimeoutMillis";
    private static final String MAX_BURST_DURATION_MILLIS = "maxBurstDurationMillis";
    private static final String DUPLICATE_WINDOW_MILLIS = "duplicateWindowMillis";
    private static final String SWEEP_CADENCE_MILLIS = "sweepCadenceMillis";
    private static final String OBSERVATION_QUEUE_CAPACITY = "observationQueueCapacity";
    private static final String PRESENTATION = "presentation";
    private static final String IO = "io";
    private static final String STORAGE = "storage";
    private static final String DEVICES = "devices";
    private static final String ANTENNA_MANAGERS = "antennaManagers";
    private static final String ANTENNAS = "antennas";
    private static final String PROVIDER = "provider";
    private static final String TYPE = "type";
    private static final String INVENTORY_GROUP = "inventoryGroup";
    private static final String MEMBERS = "members";
    private static final String INTERVAL_MILLIS = "intervalMillis";
    private static final String POWER = "power";
    private static final String TIMING_DATA = "timingData";
    private static final String LOGGING = "logging";
    private static final String PATH = "path";
    private static final String STORAGE_NODES = "nodes";

    private YamlLoader() {
    }

    public static Config load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("config path must not be null");
        }

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        Object document;
        try (InputStream input = Files.newInputStream(path)) {
            document = yaml.load(input);
        } catch (YAMLException ex) {
            throw new IllegalArgumentException("Invalid YAML configuration: " + path, ex);
        }

        document = YamlTemplateResolver.resolve(document);

        Map<?, ?> root = YamlValues.requireMapping(document, "configuration root");
        YamlValues.rejectUnknownFields(
                root,
                "configuration root",
                TIMING_SYSTEMS,
                PRESENTATION,
                LOGGING,
                IO);

        List<TimingSystemStartup> timingSystems =
                mapTimingSystems(
                        root.get(TIMING_SYSTEMS));

        List<TimingNodeStartup> allTimingNodes =
                new ArrayList<TimingNodeStartup>();
        for (TimingSystemStartup timingSystem
                : timingSystems) {
            allTimingNodes.addAll(
                    timingSystem.timingNodes);
        }

        Map<NodeId, Path> timingDataPaths =
                mapTimingDataPaths(
                        root.get(IO),
                        allTimingNodes);

        List<Config.TimingSystemConfig> configuredSystems =
                new ArrayList<Config.TimingSystemConfig>(
                        timingSystems.size());
        for (TimingSystemStartup timingSystem
                : timingSystems) {
            List<Config.TimingNodeConfig> configuredNodes =
                    new ArrayList<Config.TimingNodeConfig>(
                            timingSystem.timingNodes.size());
            for (TimingNodeStartup timingNode
                    : timingSystem.timingNodes) {
                configuredNodes.add(
                        new Config.TimingNodeConfig(
                                timingNode.timingNodeId,
                                timingDataPaths.get(
                                        timingNode.timingNodeId),
                                timingNode.tagProcessingPolicy));
            }

            configuredSystems.add(
                    new Config.TimingSystemConfig(
                            timingSystem.timingSystemId,
                            configuredNodes,
                            timingSystem.eventDataProviderId,
                            timingSystem.timingDataProviderId));
        }

        return new Config(
                configuredSystems,
                mapAntennaManagers(root.get(IO)),
                YamlPresentationMapper.map(root.get(PRESENTATION)),
                YamlLoggingMapper.map(root.get(LOGGING)),
                YamlLoggingMapper.mapLive(root.get(LOGGING)));
    }

    private static List<TimingSystemStartup> mapTimingSystems(
            Object rawTimingSystems) {
        if (rawTimingSystems == null) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + TIMING_SYSTEMS);
        }

        List<?> timingSystems =
                YamlValues.requireList(rawTimingSystems, TIMING_SYSTEMS);
        if (timingSystems.isEmpty()) {
            throw new IllegalArgumentException(
                    TIMING_SYSTEMS
                            + " must contain at least one TimingSystem");
        }

        List<TimingSystemStartup> systems =
                new ArrayList<TimingSystemStartup>(
                        timingSystems.size());
        Set<String> systemIds =
                new LinkedHashSet<String>();
        Set<NodeId> nodeIds =
                new LinkedHashSet<NodeId>();

        for (int systemIndex = 0; systemIndex < timingSystems.size(); systemIndex++) {
            String timingSystemField = TIMING_SYSTEMS + "[" + systemIndex + "]";
            Map<?, ?> timingSystem =
                    YamlValues.requireMapping(timingSystems.get(systemIndex), timingSystemField);
            YamlValues.rejectUnknownFields(
                    timingSystem,
                    timingSystemField,
                    SYSTEM_ID,
                    EVENT_DATA_PROVIDER,
                    TIMING_DATA_PROVIDER,
                    TIMING_NODES);

            if (!timingSystem.containsKey(
                    SYSTEM_ID)) {
                throw new IllegalArgumentException(
                        "Missing required configuration field: "
                                + timingSystemField
                                + "."
                                + SYSTEM_ID);
            }

            String timingSystemId =
                    YamlValues.requireCompactId(
                            timingSystem.get(
                                    SYSTEM_ID),
                            timingSystemField
                                    + "."
                                    + SYSTEM_ID)
                            .trim();
            if (timingSystemId.isEmpty()) {
                throw new IllegalArgumentException(
                        timingSystemField
                                + "."
                                + SYSTEM_ID
                                + " must not be blank");
            }
            if (!systemIds.add(
                    timingSystemId)) {
                throw new IllegalArgumentException(
                        "Duplicate TimingSystemId "
                                + timingSystemId);
            }

            if (!timingSystem.containsKey(
                    TIMING_NODES)) {
                throw new IllegalArgumentException(
                        "Missing required configuration field: "
                                + timingSystemField
                                + "."
                                + TIMING_NODES);
            }

            String timingNodesField =
                    timingSystemField
                            + "."
                            + TIMING_NODES;
            List<?> timingNodes =
                    YamlValues.requireList(timingSystem.get(TIMING_NODES), timingNodesField);
            if (timingNodes.isEmpty()) {
                throw new IllegalArgumentException(
                        timingNodesField
                                + " must contain at least one TimingNode");
            }

            List<TimingNodeStartup> nodes =
                    new ArrayList<TimingNodeStartup>(
                            timingNodes.size());

            for (int nodeIndex = 0; nodeIndex < timingNodes.size(); nodeIndex++) {
                String timingNodeField = timingNodesField + "[" + nodeIndex + "]";
                Map<?, ?> timingNode =
                        YamlValues.requireMapping(timingNodes.get(nodeIndex), timingNodeField);
                YamlValues.rejectUnknownFields(
                        timingNode,
                        timingNodeField,
                        NODE_ID,
                        TAG_PROCESSING);

                if (!timingNode.containsKey(
                        NODE_ID)) {
                    throw new IllegalArgumentException(
                            "Missing required configuration field: "
                                    + timingNodeField
                                    + "."
                                    + NODE_ID);
                }

                NodeId timingNodeId =
                        new NodeId(
                                YamlValues.requireCompactId(
                                        timingNode.get(
                                                NODE_ID),
                                        timingNodeField
                                                + "."
                                                + NODE_ID));
                if (!nodeIds.add(
                        timingNodeId)) {
                    throw new IllegalArgumentException(
                            "Duplicate application-wide TimingNodeId "
                                    + timingNodeId.value());
                }

                nodes.add(
                        new TimingNodeStartup(
                                timingSystemId,
                                timingNodeId,
                                mapTagProcessing(
                                        timingNode,
                                        timingNodeField)));
            }

            systems.add(
                    new TimingSystemStartup(
                            timingSystemId,
                            nodes,
                            providerId(
                                    timingSystem,
                                    timingSystemField,
                                    EVENT_DATA_PROVIDER),
                            providerId(
                                    timingSystem,
                                    timingSystemField,
                                    TIMING_DATA_PROVIDER)));
        }

        return systems;
    }

    private static TagProcessingPolicy mapTagProcessing(
            Map<?, ?> timingNode,
            String timingNodeField) {
        TagProcessingPolicy defaults =
                TagProcessingPolicy.defaults();
        if (!timingNode.containsKey(TAG_PROCESSING)) {
            return defaults;
        }

        String field =
                timingNodeField + "." + TAG_PROCESSING;
        Map<?, ?> values =
                YamlValues.requireMapping(
                        timingNode.get(TAG_PROCESSING),
                        field);
        YamlValues.rejectUnknownFields(
                values,
                field,
                QUIET_TIMEOUT_MILLIS,
                MAX_BURST_DURATION_MILLIS,
                DUPLICATE_WINDOW_MILLIS,
                SWEEP_CADENCE_MILLIS,
                OBSERVATION_QUEUE_CAPACITY);

        return new TagProcessingPolicy(
                durationOverride(
                        values,
                        field,
                        QUIET_TIMEOUT_MILLIS,
                        defaults.quietTimeoutNanos(),
                        false),
                durationOverride(
                        values,
                        field,
                        MAX_BURST_DURATION_MILLIS,
                        defaults.maxBurstDurationNanos(),
                        false),
                durationOverride(
                        values,
                        field,
                        DUPLICATE_WINDOW_MILLIS,
                        defaults.duplicateWindowNanos(),
                        true),
                durationOverride(
                        values,
                        field,
                        SWEEP_CADENCE_MILLIS,
                        defaults.sweepCadenceNanos(),
                        false),
                integerOverride(
                        values,
                        field,
                        OBSERVATION_QUEUE_CAPACITY,
                        defaults.observationQueueCapacity()));
    }

    private static Duration durationOverride(
            Map<?, ?> values,
            String field,
            String name,
            long defaultNanos,
            boolean zeroAllowed) {
        if (!values.containsKey(name)) {
            return Duration.ofNanos(defaultNanos);
        }

        long millis =
                requireYamlLong(
                        values.get(name),
                        field + "." + name);
        if (zeroAllowed ? millis < 0L : millis < 1L) {
            throw new IllegalArgumentException(
                    field + "." + name
                            + (zeroAllowed
                                    ? " must not be negative"
                                    : " must be positive"));
        }
        return Duration.ofMillis(millis);
    }

    private static int integerOverride(
            Map<?, ?> values,
            String field,
            String name,
            int defaultValue) {
        if (!values.containsKey(name)) {
            return defaultValue;
        }

        Object raw = values.get(name);
        if (!(raw instanceof Integer)
                || ((Integer) raw).intValue() < 1) {
            throw new IllegalArgumentException(
                    field + "." + name
                            + " must be a positive YAML integer");
        }
        return ((Integer) raw).intValue();
    }

    private static long requireYamlLong(
            Object value,
            String field) {
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof Long) {
            return ((Long) value).longValue();
        }
        throw new IllegalArgumentException(
                field + " must be a YAML integer");
    }

    private static String requireMappingEntryName(
            Object rawKey,
            String field) {
        if (!(rawKey instanceof String)
                || ((String) rawKey).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    field
                            + " entry name must be a non-blank YAML string");
        }
        return ((String) rawKey).trim();
    }

    private static List<AntennaManagerConfig> mapAntennaManagers(Object rawIo) {
        if (rawIo == null) {
            return Collections.emptyList();
        }
        Map<?, ?> io = YamlValues.requireMapping(rawIo, IO);
        if (!io.containsKey(DEVICES)) {
            return Collections.emptyList();
        }
        Map<?, ?> devices = YamlValues.requireMapping(io.get(DEVICES), IO + "." + DEVICES);
        YamlValues.rejectUnknownFields(devices, IO + "." + DEVICES, ANTENNA_MANAGERS);
        if (!devices.containsKey(ANTENNA_MANAGERS)) {
            return Collections.emptyList();
        }
        String root = IO + "." + DEVICES + "." + ANTENNA_MANAGERS;
        List<?> bindings = YamlValues.requireList(devices.get(ANTENNA_MANAGERS), root);
        List<AntennaManagerConfig> result = new ArrayList<AntennaManagerConfig>();
        for (int bindingIndex = 0; bindingIndex < bindings.size(); bindingIndex++) {
            String field = root + "[" + bindingIndex + "]";
            Map<?, ?> binding = YamlValues.requireMapping(bindings.get(bindingIndex), field);
            YamlValues.rejectUnknownFields(binding, field, SYSTEM_ID, ANTENNAS, INVENTORY_GROUP);
            String systemId = YamlValues.requireCompactId(binding.get(SYSTEM_ID),
                    field + "." + SYSTEM_ID);
            String antennasField = field + "." + ANTENNAS;
            List<?> antennas = YamlValues.requireList(binding.get(ANTENNAS), antennasField);
            List<AntennaManagerConfig.AntennaConfig> configured =
                    new ArrayList<AntennaManagerConfig.AntennaConfig>();
            for (int antennaIndex = 0; antennaIndex < antennas.size(); antennaIndex++) {
                String antennaField = antennasField + "[" + antennaIndex + "]";
                Map<?, ?> values = YamlValues.requireMapping(antennas.get(antennaIndex), antennaField);
                YamlValues.rejectUnknownFields(values, antennaField,
                        "id", PROVIDER, TYPE, TIMING_NODES, POWER);
                AntennaId antennaId = new AntennaId(
                        YamlValues.requireCompactId(values.get("id"), antennaField + ".id"));
                String type = YamlValues.requireString(values.get(TYPE), antennaField + "." + TYPE);
                if (!"rfid".equals(type)) {
                    throw new IllegalArgumentException(antennaField + ".type must be rfid");
                }
                if (values.containsKey(POWER)) {
                    throw new IllegalArgumentException(
                            antennaField + ".power is not supported without an external power registry");
                }
                List<?> rawNodes = YamlValues.requireList(values.get(TIMING_NODES),
                        antennaField + "." + TIMING_NODES);
                List<NodeId> nodes = new ArrayList<NodeId>();
                for (Object rawNode : rawNodes) {
                    nodes.add(new NodeId(YamlValues.requireCompactId(
                            rawNode, antennaField + "." + TIMING_NODES)));
                }
                configured.add(new AntennaManagerConfig.AntennaConfig(
                        antennaId,
                        YamlValues.requireString(values.get(PROVIDER), antennaField + "." + PROVIDER),
                        nodes));
            }
            List<AntennaId> members = new ArrayList<AntennaId>();
            Duration interval = null;
            if (binding.containsKey(INVENTORY_GROUP)) {
                String groupField = field + "." + INVENTORY_GROUP;
                Map<?, ?> group = YamlValues.requireMapping(binding.get(INVENTORY_GROUP), groupField);
                YamlValues.rejectUnknownFields(group, groupField, MEMBERS, INTERVAL_MILLIS);
                Object rawMembers = group.get(MEMBERS);
                if (!(rawMembers instanceof List)) {
                    throw new IllegalArgumentException(groupField + ".members must be a YAML list");
                }
                for (Object member : (List<?>) rawMembers) {
                    members.add(new AntennaId(String.valueOf(member)));
                }
                long millis = requireYamlLong(group.get(INTERVAL_MILLIS),
                        groupField + "." + INTERVAL_MILLIS);
                interval = Duration.ofMillis(millis);
            }
            result.add(new AntennaManagerConfig(systemId, configured, members, interval));
        }
        return result;
    }

    private static Map<NodeId, Path> mapTimingDataPaths(
            Object rawIo,
            List<TimingNodeStartup> timingNodes) {
        if (rawIo == null) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: " + IO);
        }

        Map<?, ?> io = YamlValues.requireMapping(rawIo, IO);
        YamlValues.rejectUnknownFields(io, IO, STORAGE, DEVICES);
        if (!io.containsKey(STORAGE)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + IO + "." + STORAGE);
        }

        String storageField = IO + "." + STORAGE;
        Map<?, ?> storage =
                YamlValues.requireMapping(
                        io.get(STORAGE),
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
                storageField + "." + TIMING_DATA;
        Map<?, ?> timingData =
                YamlValues.requireMapping(
                        storage.get(TIMING_DATA),
                        timingDataField);
        YamlValues.rejectUnknownFields(
                timingData,
                timingDataField,
                PATH,
                STORAGE_NODES);

        boolean hasPath =
                timingData.containsKey(PATH);
        boolean hasNodes =
                timingData.containsKey(STORAGE_NODES);
        if (hasPath == hasNodes) {
            throw new IllegalArgumentException(
                    timingDataField
                            + " must define exactly one of path or nodes");
        }

        if (hasPath) {
            String field =
                    timingDataField + "." + PATH;
            String template =
                    mapPathTemplate(
                            timingData.get(PATH),
                            field);
            if (timingNodes.size() != 1
                    && !YamlTemplateResolver
                            .hasContextPlaceholder(template)) {
                throw new IllegalArgumentException(
                        field
                                + " without {NodeId} or {SystemId} is only valid for exactly one TimingNode");
            }

            Map<NodeId, Path> result =
                    new LinkedHashMap<NodeId, Path>();
            Set<Path> normalizedPaths =
                    new LinkedHashSet<Path>();
            for (TimingNodeStartup timingNode
                    : timingNodes) {
                addTimingDataPath(
                        result,
                        normalizedPaths,
                        timingNode.timingNodeId,
                        mapPath(
                                template,
                                field,
                                timingNode));
            }
            return result;
        }

        String nodesField =
                timingDataField + "." + STORAGE_NODES;
        Map<?, ?> bindings =
                YamlValues.requireMapping(
                        timingData.get(STORAGE_NODES),
                        nodesField);
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException(
                    nodesField
                            + " must contain at least one storage binding");
        }

        Map<NodeId, TimingNodeStartup> configuredNodes =
                new LinkedHashMap<NodeId, TimingNodeStartup>();
        for (TimingNodeStartup timingNode
                : timingNodes) {
            configuredNodes.put(
                    timingNode.timingNodeId,
                    timingNode);
        }

        Map<NodeId, Path> result =
                new LinkedHashMap<NodeId, Path>();
        Set<Path> normalizedPaths =
                new LinkedHashSet<Path>();

        for (Map.Entry<?, ?> entry
                : bindings.entrySet()) {
            String bindingName =
                    requireMappingEntryName(
                            entry.getKey(),
                            nodesField);
            String bindingField =
                    nodesField + "." + bindingName;
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
                                    binding.get(NODE_ID),
                                    bindingField
                                            + "."
                                            + NODE_ID));
            TimingNodeStartup configuredNode =
                    configuredNodes.get(nodeId);
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
                    bindingField + "." + PATH;
            addTimingDataPath(
                    result,
                    normalizedPaths,
                    nodeId,
                    mapPath(
                            mapPathTemplate(
                                    binding.get(PATH),
                                    field),
                            field,
                            configuredNode));
        }

        if (result.size() != configuredNodes.size()) {
            for (NodeId nodeId
                    : configuredNodes.keySet()) {
                if (!result.containsKey(nodeId)) {
                    throw new IllegalArgumentException(
                            "Missing TimingData storage binding for TimingNode "
                                    + nodeId.value());
                }
            }
        }

        return result;
    }

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
                    field + " must not be blank");
        }
        return value;
    }

    private static Path mapPath(
            String template,
            String field,
            TimingNodeStartup timingNode) {
        String value =
                YamlTemplateResolver.resolveContext(
                        template,
                        field,
                        timingNode.timingNodeId.value(),
                        timingNode.timingSystemId);

        try {
            return Paths.get(value);
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException(
                    field + " is not a valid filesystem path",
                    ex);
        }
    }

    private static void addTimingDataPath(
            Map<NodeId, Path> result,
            Set<Path> normalizedPaths,
            NodeId nodeId,
            Path path) {
        Path normalized =
                path.toAbsolutePath().normalize();
        if (!normalizedPaths.add(normalized)) {
            throw new IllegalArgumentException(
                    "Duplicate TimingData storage path "
                            + path);
        }
        result.put(
                nodeId,
                path);
    }

    private static String providerId(
            Map<?, ?> timingSystem,
            String timingSystemField,
            String name) {
        if (!timingSystem.containsKey(name)) {
            return Config.REFERENCE_PROVIDER_ID;
        }

        String value =
                YamlValues.requireString(
                        timingSystem.get(name),
                        timingSystemField + "." + name)
                        .trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    timingSystemField
                            + "."
                            + name
                            + " must not be blank");
        }
        return value;
    }

    private static final class TimingNodeStartup {
        private final String timingSystemId;
        private final NodeId timingNodeId;
        private final TagProcessingPolicy tagProcessingPolicy;

        private TimingNodeStartup(
                String timingSystemId,
                NodeId timingNodeId,
                TagProcessingPolicy tagProcessingPolicy) {
            this.timingSystemId = timingSystemId;
            this.timingNodeId = timingNodeId;
            this.tagProcessingPolicy = tagProcessingPolicy;
        }
    }

    private static final class TimingSystemStartup {
        private final String timingSystemId;
        private final List<TimingNodeStartup> timingNodes;
        private final String eventDataProviderId;
        private final String timingDataProviderId;

        private TimingSystemStartup(
                String timingSystemId,
                List<TimingNodeStartup> timingNodes,
                String eventDataProviderId,
                String timingDataProviderId) {
            this.timingSystemId = timingSystemId;
            this.timingNodes =
                    Collections.unmodifiableList(
                            new ArrayList<TimingNodeStartup>(
                                    timingNodes));
            this.eventDataProviderId = eventDataProviderId;
            this.timingDataProviderId = timingDataProviderId;
        }
    }

}
