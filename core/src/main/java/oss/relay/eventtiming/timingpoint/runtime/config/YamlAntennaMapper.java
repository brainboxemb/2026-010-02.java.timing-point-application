package oss.relay.eventtiming.timingpoint.runtime.config;

import oss.relay.eventtiming.timingpoint.io.devices.antenna.AntennaId;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Maps antenna-manager devices from the IO YAML section. */
final class YamlAntennaMapper {
    private static final String IO = "io";
    private static final String DEVICES = "devices";
    private static final String ANTENNA_MANAGERS = "antennaManagers";
    private static final String ANTENNAS = "antennas";
    private static final String SYSTEM_ID = "systemId";
    private static final String PROVIDER = "provider";
    private static final String TYPE = "type";
    private static final String POWER = "power";
    private static final String INVENTORY_GROUP = "inventoryGroup";
    private static final String MEMBERS = "members";
    private static final String INTERVAL_MILLIS = "intervalMillis";

    private YamlAntennaMapper() {
    }

    /** Maps optional antenna-manager devices and inventory-group bindings. */
    static List<AntennaManagerConfig> map(Object rawIo) {
        if (rawIo == null) {
            return Collections.emptyList();
        }

        Map<?, ?> io =
                YamlValues.requireMapping(
                        rawIo,
                        IO);
        if (!io.containsKey(DEVICES)) {
            return Collections.emptyList();
        }

        Map<?, ?> devices =
                YamlValues.requireMapping(
                        io.get(
                                DEVICES),
                        IO
                                + "."
                                + DEVICES);
        YamlValues.rejectUnknownFields(
                devices,
                IO
                        + "."
                        + DEVICES,
                ANTENNA_MANAGERS);
        if (!devices.containsKey(ANTENNA_MANAGERS)) {
            return Collections.emptyList();
        }

        String root =
                IO
                        + "."
                        + DEVICES
                        + "."
                        + ANTENNA_MANAGERS;
        List<?> bindings =
                YamlValues.requireList(
                        devices.get(
                                ANTENNA_MANAGERS),
                        root);
        List<AntennaManagerConfig> result =
                new ArrayList<AntennaManagerConfig>();

        for (int bindingIndex = 0;
                bindingIndex < bindings.size();
                bindingIndex++) {
            String field =
                    root
                            + "["
                            + bindingIndex
                            + "]";
            Map<?, ?> binding =
                    YamlValues.requireMapping(
                            bindings.get(
                                    bindingIndex),
                            field);
            YamlValues.rejectUnknownFields(
                    binding,
                    field,
                    SYSTEM_ID,
                    ANTENNAS,
                    INVENTORY_GROUP);

            String systemId =
                    YamlValues.requireCompactId(
                            binding.get(
                                    SYSTEM_ID),
                            field
                                    + "."
                                    + SYSTEM_ID);
            String antennasField =
                    field
                            + "."
                            + ANTENNAS;
            List<?> antennas =
                    YamlValues.requireList(
                            binding.get(
                                    ANTENNAS),
                            antennasField);
            List<AntennaManagerConfig.AntennaConfig> configured =
                    new ArrayList<AntennaManagerConfig.AntennaConfig>();

            for (int antennaIndex = 0;
                    antennaIndex < antennas.size();
                    antennaIndex++) {
                String antennaField =
                        antennasField
                                + "["
                                + antennaIndex
                                + "]";
                Map<?, ?> values =
                        YamlValues.requireMapping(
                                antennas.get(
                                        antennaIndex),
                                antennaField);
                YamlValues.rejectUnknownFields(
                        values,
                        antennaField,
                        "id",
                        PROVIDER,
                        TYPE,
                        POWER);

                AntennaId antennaId =
                        new AntennaId(
                                YamlValues.requireCompactId(
                                        values.get(
                                                "id"),
                                        antennaField
                                                + ".id"));
                String type =
                        YamlValues.requireString(
                                values.get(
                                        TYPE),
                                antennaField
                                        + "."
                                        + TYPE);
                if (!"rfid".equals(type)) {
                    throw new IllegalArgumentException(
                            antennaField
                                    + ".type must be rfid");
                }
                if (values.containsKey(POWER)) {
                    throw new IllegalArgumentException(
                            antennaField
                                    + ".power is not supported without an external power registry");
                }

                configured.add(
                        new AntennaManagerConfig.AntennaConfig(
                                antennaId,
                                YamlValues.requireString(
                                        values.get(
                                                PROVIDER),
                                        antennaField
                                                + "."
                                                + PROVIDER)));
            }

            List<AntennaId> members =
                    new ArrayList<AntennaId>();
            Duration interval = null;
            if (binding.containsKey(INVENTORY_GROUP)) {
                String groupField =
                        field
                                + "."
                                + INVENTORY_GROUP;
                Map<?, ?> group =
                        YamlValues.requireMapping(
                                binding.get(
                                        INVENTORY_GROUP),
                                groupField);
                YamlValues.rejectUnknownFields(
                        group,
                        groupField,
                        MEMBERS,
                        INTERVAL_MILLIS);

                Object rawMembers =
                        group.get(
                                MEMBERS);
                if (!(rawMembers instanceof List)) {
                    throw new IllegalArgumentException(
                            groupField
                                    + ".members must be a YAML list");
                }
                for (Object member : (List<?>) rawMembers) {
                    members.add(
                            new AntennaId(
                                    String.valueOf(
                                            member)));
                }

                long millis =
                        YamlValues.requireLong(
                                group.get(
                                        INTERVAL_MILLIS),
                                groupField
                                        + "."
                                        + INTERVAL_MILLIS);
                interval =
                        Duration.ofMillis(
                                millis);
            }

            result.add(
                    new AntennaManagerConfig(
                            systemId,
                            configured,
                            members,
                            interval));
        }

        return result;
    }
}
