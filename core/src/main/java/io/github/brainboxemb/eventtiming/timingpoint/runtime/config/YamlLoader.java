package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingConfig;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingFileConfig;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevel;
import io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServerConfig;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String TIMING_SYSTEM_ID = "timingSystemId";
    private static final String EVENT_DATA_PROVIDER = "eventDataProvider";
    private static final String TIMING_DATA_PROVIDER = "timingDataProvider";
    private static final String TIMING_NODES = "timingNodes";
    private static final String TIMING_NODE_ID = "timingNodeId";
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
    private static final String LEVEL = "level";
    private static final String FILE = "file";
    private static final String PATH = "path";
    private static final String STORAGE_NODES = "nodes";
    private static final String ROTATE_BYTES = "rotateBytes";
    private static final String RETAINED_FILES = "retainedFiles";
    private static final String LIVE = "live";
    private static final String REMOTE_SHELL = "remoteShell";
    private static final String API = "api";
    private static final String HTTP = "http";
    private static final String WEB_SOCKET = "webSocket";
    private static final String BIND_ADDRESS = "bindAddress";
    private static final String PORT = "port";

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

        Map<?, ?> root = requireMapping(document, "configuration root");
        rejectUnknownFields(
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
                mapPresentation(root.get(PRESENTATION)),
                mapLogging(root.get(LOGGING)),
                mapLoggingLive(root.get(LOGGING)));
    }

    private static List<TimingSystemStartup> mapTimingSystems(
            Object rawTimingSystems) {
        if (rawTimingSystems == null) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + TIMING_SYSTEMS);
        }

        Map<?, ?> timingSystems =
                requireMapping(
                        rawTimingSystems,
                        TIMING_SYSTEMS);
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

        for (Map.Entry<?, ?> systemEntry
                : timingSystems.entrySet()) {
            String timingSystemKey =
                    requireMappingEntryName(
                            systemEntry.getKey(),
                            TIMING_SYSTEMS);
            String timingSystemField =
                    TIMING_SYSTEMS + "." + timingSystemKey;
            Map<?, ?> timingSystem =
                    requireMapping(
                            systemEntry.getValue(),
                            timingSystemField);
            rejectUnknownFields(
                    timingSystem,
                    timingSystemField,
                    TIMING_SYSTEM_ID,
                    EVENT_DATA_PROVIDER,
                    TIMING_DATA_PROVIDER,
                    TIMING_NODES);

            if (!timingSystem.containsKey(
                    TIMING_SYSTEM_ID)) {
                throw new IllegalArgumentException(
                        "Missing required configuration field: "
                                + timingSystemField
                                + "."
                                + TIMING_SYSTEM_ID);
            }

            String timingSystemId =
                    requireString(
                            timingSystem.get(
                                    TIMING_SYSTEM_ID),
                            timingSystemField
                                    + "."
                                    + TIMING_SYSTEM_ID)
                            .trim();
            if (timingSystemId.isEmpty()) {
                throw new IllegalArgumentException(
                        timingSystemField
                                + "."
                                + TIMING_SYSTEM_ID
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
            Map<?, ?> timingNodes =
                    requireMapping(
                            timingSystem.get(
                                    TIMING_NODES),
                            timingNodesField);
            if (timingNodes.isEmpty()) {
                throw new IllegalArgumentException(
                        timingNodesField
                                + " must contain at least one TimingNode");
            }

            List<TimingNodeStartup> nodes =
                    new ArrayList<TimingNodeStartup>(
                            timingNodes.size());

            for (Map.Entry<?, ?> nodeEntry
                    : timingNodes.entrySet()) {
                String timingNodeKey =
                        requireMappingEntryName(
                                nodeEntry.getKey(),
                                timingNodesField);
                String timingNodeField =
                        timingNodesField
                                + "."
                                + timingNodeKey;
                Map<?, ?> timingNode =
                        requireMapping(
                                nodeEntry.getValue(),
                                timingNodeField);
                rejectUnknownFields(
                        timingNode,
                        timingNodeField,
                        TIMING_NODE_ID,
                        TAG_PROCESSING);

                if (!timingNode.containsKey(
                        TIMING_NODE_ID)) {
                    throw new IllegalArgumentException(
                            "Missing required configuration field: "
                                    + timingNodeField
                                    + "."
                                    + TIMING_NODE_ID);
                }

                NodeId timingNodeId =
                        new NodeId(
                                requireString(
                                        timingNode.get(
                                                TIMING_NODE_ID),
                                        timingNodeField
                                                + "."
                                                + TIMING_NODE_ID));
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
                requireMapping(
                        timingNode.get(TAG_PROCESSING),
                        field);
        rejectUnknownFields(
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
        Map<?, ?> io = requireMapping(rawIo, IO);
        if (!io.containsKey(DEVICES)) {
            return Collections.emptyList();
        }
        Map<?, ?> devices = requireMapping(io.get(DEVICES), IO + "." + DEVICES);
        rejectUnknownFields(devices, IO + "." + DEVICES, ANTENNA_MANAGERS);
        if (!devices.containsKey(ANTENNA_MANAGERS)) {
            return Collections.emptyList();
        }
        String root = IO + "." + DEVICES + "." + ANTENNA_MANAGERS;
        Map<?, ?> bindings = requireMapping(devices.get(ANTENNA_MANAGERS), root);
        List<AntennaManagerConfig> result = new ArrayList<AntennaManagerConfig>();
        for (Map.Entry<?, ?> entry : bindings.entrySet()) {
            String field = root + "." + requireMappingEntryName(entry.getKey(), root);
            Map<?, ?> binding = requireMapping(entry.getValue(), field);
            rejectUnknownFields(binding, field, TIMING_SYSTEM_ID, ANTENNAS, INVENTORY_GROUP);
            String systemId = requireString(binding.get(TIMING_SYSTEM_ID),
                    field + "." + TIMING_SYSTEM_ID);
            String antennasField = field + "." + ANTENNAS;
            Map<?, ?> antennas = requireMapping(binding.get(ANTENNAS), antennasField);
            List<AntennaManagerConfig.AntennaConfig> configured =
                    new ArrayList<AntennaManagerConfig.AntennaConfig>();
            for (Map.Entry<?, ?> antennaEntry : antennas.entrySet()) {
                // SnakeYAML reads unquoted numeric keys as Integer; IDs remain 1..9.
                String id = String.valueOf(antennaEntry.getKey());
                AntennaId antennaId = new AntennaId(id);
                String antennaField = antennasField + "." + id;
                Map<?, ?> values = requireMapping(antennaEntry.getValue(), antennaField);
                rejectUnknownFields(values, antennaField,
                        PROVIDER, TYPE, TIMING_NODES, POWER);
                String type = requireString(values.get(TYPE), antennaField + "." + TYPE);
                if (!"rfid".equals(type)) {
                    throw new IllegalArgumentException(antennaField + ".type must be rfid");
                }
                if (values.containsKey(POWER)) {
                    throw new IllegalArgumentException(
                            antennaField + ".power is not supported without an external power registry");
                }
                Object rawNodes = values.get(TIMING_NODES);
                if (!(rawNodes instanceof List)) {
                    throw new IllegalArgumentException(antennaField + ".timingNodes must be a YAML list");
                }
                List<NodeId> nodes = new ArrayList<NodeId>();
                for (Object rawNode : (List<?>) rawNodes) {
                    nodes.add(new NodeId(requireString(rawNode, antennaField + ".timingNodes")));
                }
                configured.add(new AntennaManagerConfig.AntennaConfig(
                        antennaId,
                        requireString(values.get(PROVIDER), antennaField + "." + PROVIDER),
                        nodes));
            }
            List<AntennaId> members = new ArrayList<AntennaId>();
            Duration interval = null;
            if (binding.containsKey(INVENTORY_GROUP)) {
                String groupField = field + "." + INVENTORY_GROUP;
                Map<?, ?> group = requireMapping(binding.get(INVENTORY_GROUP), groupField);
                rejectUnknownFields(group, groupField, MEMBERS, INTERVAL_MILLIS);
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

        Map<?, ?> io = requireMapping(rawIo, IO);
        rejectUnknownFields(io, IO, STORAGE, DEVICES);
        if (!io.containsKey(STORAGE)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + IO + "." + STORAGE);
        }

        String storageField = IO + "." + STORAGE;
        Map<?, ?> storage =
                requireMapping(
                        io.get(STORAGE),
                        storageField);
        rejectUnknownFields(
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
                requireMapping(
                        storage.get(TIMING_DATA),
                        timingDataField);
        rejectUnknownFields(
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
                requireMapping(
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
                    requireMapping(
                            entry.getValue(),
                            bindingField);
            rejectUnknownFields(
                    binding,
                    bindingField,
                    TIMING_NODE_ID,
                    PATH);

            NodeId nodeId =
                    new NodeId(
                            requireString(
                                    binding.get(TIMING_NODE_ID),
                                    bindingField
                                            + "."
                                            + TIMING_NODE_ID));
            TimingNodeStartup configuredNode =
                    configuredNodes.get(nodeId);
            if (configuredNode == null) {
                throw new IllegalArgumentException(
                        bindingField
                                + "."
                                + TIMING_NODE_ID
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
                requireTemplateString(
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

    private static LoggingConfig mapLogging(Object rawLogging) {
        if (rawLogging == null) {
            return null;
        }

        Map<?, ?> logging = requireMapping(rawLogging, LOGGING);
        rejectUnknownFields(logging, LOGGING, LEVEL, FILE, LIVE);
        if (!logging.containsKey(LEVEL)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: " + LOGGING + "." + LEVEL);
        }
        if (!logging.containsKey(FILE)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: " + LOGGING + "." + FILE);
        }

        String rawLevel = requireString(logging.get(LEVEL), LOGGING + "." + LEVEL);
        LoggingLevel level;
        try {
            level = LoggingLevel.valueOf(rawLevel.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    LOGGING + "." + LEVEL + " must be TRACE, DEBUG, INFO, WARN or ERROR",
                    ex);
        }

        return new LoggingConfig(level, mapLoggingFile(logging.get(FILE)));
    }

    private static LoggingFileConfig mapLoggingFile(Object rawFile) {
        String field = LOGGING + "." + FILE;
        Map<?, ?> values = requireMapping(rawFile, field);
        rejectUnknownFields(values, field, PATH, ROTATE_BYTES, RETAINED_FILES);
        return new LoggingFileConfig(
                requireString(values.get(PATH), field + "." + PATH),
                requirePositiveInteger(values.get(ROTATE_BYTES), field + "." + ROTATE_BYTES),
                requirePositiveInteger(values.get(RETAINED_FILES), field + "." + RETAINED_FILES));
    }

    private static LoggingServerConfig mapLoggingLive(Object rawLogging) {
        if (rawLogging == null) {
            return null;
        }
        Map<?, ?> logging = requireMapping(rawLogging, LOGGING);
        Object rawLive = logging.get(LIVE);
        if (rawLive == null) {
            return null;
        }
        String field = LOGGING + "." + LIVE;
        Map<?, ?> values = endpointMapping(rawLive, field);
        return new LoggingServerConfig(
                requireString(values.get(BIND_ADDRESS), field + "." + BIND_ADDRESS),
                requirePort(values.get(PORT), field + "." + PORT));
    }

    private static Presentation mapPresentation(Object rawPresentation) {
        if (rawPresentation == null) {
            return new Presentation(null, null);
        }

        Map<?, ?> presentation = requireMapping(rawPresentation, PRESENTATION);
        rejectUnknownFields(presentation, PRESENTATION, REMOTE_SHELL, API);

        return new Presentation(
                mapRemoteShell(presentation.get(REMOTE_SHELL)),
                mapApi(presentation.get(API)));
    }

    private static Presentation.RemoteShell mapRemoteShell(Object raw) {
        if (raw == null) {
            return null;
        }
        Map<?, ?> values = endpointMapping(raw, PRESENTATION + "." + REMOTE_SHELL);
        return new Presentation.RemoteShell(
                requireString(values.get(BIND_ADDRESS),
                        PRESENTATION + "." + REMOTE_SHELL + "." + BIND_ADDRESS),
                requirePort(values.get(PORT),
                        PRESENTATION + "." + REMOTE_SHELL + "." + PORT));
    }

    private static Api mapApi(Object raw) {
        if (raw == null) {
            return null;
        }
        String field = PRESENTATION + "." + API;
        Map<?, ?> values = requireMapping(raw, field);
        rejectUnknownFields(values, field, HTTP, WEB_SOCKET);
        return new Api(
                mapApiHttp(values.get(HTTP)),
                mapApiWebSocket(values.get(WEB_SOCKET)));
    }

    private static Api.Http mapApiHttp(Object raw) {
        if (raw == null) {
            return null;
        }
        String field = PRESENTATION + "." + API + "." + HTTP;
        Map<?, ?> values = endpointMapping(raw, field);
        return new Api.Http(
                requireString(values.get(BIND_ADDRESS), field + "." + BIND_ADDRESS),
                requirePort(values.get(PORT), field + "." + PORT));
    }

    private static Api.WebSocket mapApiWebSocket(Object raw) {
        if (raw == null) {
            return null;
        }
        String field = PRESENTATION + "." + API + "." + WEB_SOCKET;
        Map<?, ?> values = endpointMapping(raw, field);
        return new Api.WebSocket(
                requireString(values.get(BIND_ADDRESS), field + "." + BIND_ADDRESS),
                requirePort(values.get(PORT), field + "." + PORT));
    }

    private static Map<?, ?> endpointMapping(Object raw, String field) {
        Map<?, ?> values = requireMapping(raw, field);
        rejectUnknownFields(values, field, BIND_ADDRESS, PORT);
        if (!values.containsKey(BIND_ADDRESS)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: " + field + "." + BIND_ADDRESS);
        }
        if (!values.containsKey(PORT)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: " + field + "." + PORT);
        }
        return values;
    }

    private static int requirePositiveInteger(Object value, String field) {
        if (!(value instanceof Integer) || ((Integer) value).intValue() <= 0) {
            throw new IllegalArgumentException(field + " must be a positive YAML integer");
        }
        return ((Integer) value).intValue();
    }

    private static int requirePort(Object value, String field) {
        if (!(value instanceof Integer)) {
            throw new IllegalArgumentException(field + " must be a YAML integer");
        }
        return ((Integer) value).intValue();
    }

    private static Map<?, ?> requireMapping(Object value, String field) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(field + " must be a YAML mapping");
        }
        return (Map<?, ?>) value;
    }

    private static String requireString(Object value, String field) {
        String result =
                requireTemplateString(
                        value,
                        field);
        YamlTemplateResolver.rejectContextPlaceholders(
                result,
                field);
        return result;
    }

    private static String requireTemplateString(
            Object value,
            String field) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    field + " must be a YAML string");
        }
        return (String) value;
    }

    private static String providerId(
            Map<?, ?> timingSystem,
            String timingSystemField,
            String name) {
        if (!timingSystem.containsKey(name)) {
            return Config.REFERENCE_PROVIDER_ID;
        }

        String value =
                requireString(
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

    private static void rejectUnknownFields(
            Map<?, ?> values,
            String field,
            String... allowedFields) {
        for (Object key : values.keySet()) {
            if (!(key instanceof String)
                    || !Arrays.asList(allowedFields).contains((String) key)) {
                throw new IllegalArgumentException(
                        "Unsupported configuration field in " + field + ": " + key);
            }
        }
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
