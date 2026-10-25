package oss.relay.eventtiming.timingpoint.runtime.config;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.AntennaId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Maps TimingSystem, TimingNode and TagProcessor policy YAML. */
final class YamlTopologyMapper {
    private static final String TIMING_SYSTEMS = "timingSystems";
    private static final String SYSTEM_ID = "systemId";
    private static final String EVENT_DATA_PROVIDER = "eventDataProvider";
    private static final String TIMING_DATA_PROVIDER = "timingDataProvider";
    private static final String TIMING_NODES = "timingNodes";
    private static final String NODE_ID = "nodeId";
    private static final String ANTENNA_ID = "antennaId";
    private static final String TAG_PROCESSING = "tagProcessing";
    private static final String QUIET_TIMEOUT_MILLIS = "quietTimeoutMillis";
    private static final String MAX_BURST_DURATION_MILLIS =
            "maxBurstDurationMillis";
    private static final String DUPLICATE_WINDOW_MILLIS =
            "duplicateWindowMillis";
    private static final String SWEEP_CADENCE_MILLIS =
            "sweepCadenceMillis";
    private static final String OBSERVATION_QUEUE_CAPACITY =
            "observationQueueCapacity";

    private YamlTopologyMapper() {
    }

    /** Maps the required ordered TimingSystem topology. */
    static List<TimingSystemStartup> map(Object rawTimingSystems) {
        if (rawTimingSystems == null) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + TIMING_SYSTEMS);
        }

        List<?> timingSystems =
                YamlValues.requireList(
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

        for (int systemIndex = 0;
                systemIndex < timingSystems.size();
                systemIndex++) {
            String timingSystemField =
                    TIMING_SYSTEMS
                            + "["
                            + systemIndex
                            + "]";
            Map<?, ?> timingSystem =
                    YamlValues.requireMapping(
                            timingSystems.get(
                                    systemIndex),
                            timingSystemField);
            YamlValues.rejectUnknownFields(
                    timingSystem,
                    timingSystemField,
                    SYSTEM_ID,
                    EVENT_DATA_PROVIDER,
                    TIMING_DATA_PROVIDER,
                    TIMING_NODES);

            if (!timingSystem.containsKey(SYSTEM_ID)) {
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
            if (!systemIds.add(timingSystemId)) {
                throw new IllegalArgumentException(
                        "Duplicate TimingSystemId "
                                + timingSystemId);
            }

            if (!timingSystem.containsKey(TIMING_NODES)) {
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
                    YamlValues.requireList(
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
            for (int nodeIndex = 0;
                    nodeIndex < timingNodes.size();
                    nodeIndex++) {
                String timingNodeField =
                        timingNodesField
                                + "["
                                + nodeIndex
                                + "]";
                Map<?, ?> timingNode =
                        YamlValues.requireMapping(
                                timingNodes.get(
                                        nodeIndex),
                                timingNodeField);
                YamlValues.rejectUnknownFields(
                        timingNode,
                        timingNodeField,
                        NODE_ID,
                        ANTENNA_ID,
                        TAG_PROCESSING);

                if (!timingNode.containsKey(NODE_ID)) {
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
                AntennaId antennaId =
                        timingNode.containsKey(ANTENNA_ID)
                                ? new AntennaId(
                                        YamlValues.requireCompactId(
                                                timingNode.get(
                                                        ANTENNA_ID),
                                                timingNodeField
                                                        + "."
                                                        + ANTENNA_ID))
                                : null;
                if (!nodeIds.add(timingNodeId)) {
                    throw new IllegalArgumentException(
                            "Duplicate application-wide TimingNodeId "
                                    + timingNodeId.value());
                }

                nodes.add(
                        new TimingNodeStartup(
                                timingSystemId,
                                timingNodeId,
                                antennaId,
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

    /** Maps optional per-node TagProcessor policy overrides. */
    private static TagProcessingPolicy mapTagProcessing(
            Map<?, ?> timingNode,
            String timingNodeField) {
        TagProcessingPolicy defaults =
                TagProcessingPolicy.defaults();
        if (!timingNode.containsKey(TAG_PROCESSING)) {
            return defaults;
        }

        String field =
                timingNodeField
                        + "."
                        + TAG_PROCESSING;
        Map<?, ?> values =
                YamlValues.requireMapping(
                        timingNode.get(
                                TAG_PROCESSING),
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
                        defaults.maxPassageDurationNanos(),
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

    /** Resolves one duration override or the supplied default. */
    private static Duration durationOverride(
            Map<?, ?> values,
            String field,
            String name,
            long defaultNanos,
            boolean zeroAllowed) {
        if (!values.containsKey(name)) {
            return Duration.ofNanos(
                    defaultNanos);
        }

        long millis =
                YamlValues.requireLong(
                        values.get(
                                name),
                        field
                                + "."
                                + name);
        if (zeroAllowed
                ? millis < 0L
                : millis < 1L) {
            throw new IllegalArgumentException(
                    field
                            + "."
                            + name
                            + (zeroAllowed
                                    ? " must not be negative"
                                    : " must be positive"));
        }
        return Duration.ofMillis(
                millis);
    }

    /** Resolves one positive integer override or the supplied default. */
    private static int integerOverride(
            Map<?, ?> values,
            String field,
            String name,
            int defaultValue) {
        if (!values.containsKey(name)) {
            return defaultValue;
        }

        Object raw =
                values.get(
                        name);
        if (!(raw instanceof Integer)
                || ((Integer) raw).intValue() < 1) {
            throw new IllegalArgumentException(
                    field
                            + "."
                            + name
                            + " must be a positive YAML integer");
        }
        return ((Integer) raw).intValue();
    }

    /** Resolves an optional provider selection to its effective provider id. */
    private static String providerId(
            Map<?, ?> timingSystem,
            String timingSystemField,
            String name) {
        if (!timingSystem.containsKey(name)) {
            return Config.REFERENCE_PROVIDER_ID;
        }

        String value =
                YamlValues.requireString(
                        timingSystem.get(
                                name),
                        timingSystemField
                                + "."
                                + name)
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

    /** Interim node topology used until storage paths are resolved. */
    static final class TimingNodeStartup {
        private final String timingSystemId;
        private final NodeId timingNodeId;
        private final AntennaId antennaId;
        private final TagProcessingPolicy tagProcessingPolicy;

        TimingNodeStartup(
                String timingSystemId,
                NodeId timingNodeId,
                AntennaId antennaId,
                TagProcessingPolicy tagProcessingPolicy) {
            this.timingSystemId = timingSystemId;
            this.timingNodeId = timingNodeId;
            this.antennaId = antennaId;
            this.tagProcessingPolicy = tagProcessingPolicy;
        }

        String timingSystemId() {
            return timingSystemId;
        }

        NodeId timingNodeId() {
            return timingNodeId;
        }

        AntennaId antennaId() {
            return antennaId;
        }

        TagProcessingPolicy tagProcessingPolicy() {
            return tagProcessingPolicy;
        }
    }

    /** Interim system topology used until storage paths are resolved. */
    static final class TimingSystemStartup {
        private final String timingSystemId;
        private final List<TimingNodeStartup> timingNodes;
        private final String eventDataProviderId;
        private final String timingDataProviderId;

        TimingSystemStartup(
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

        String timingSystemId() {
            return timingSystemId;
        }

        List<TimingNodeStartup> timingNodes() {
            return timingNodes;
        }

        String eventDataProviderId() {
            return eventDataProviderId;
        }

        String timingDataProviderId() {
            return timingDataProviderId;
        }
    }
}
