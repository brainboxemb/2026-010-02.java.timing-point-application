package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlTopologyMapper.TimingNodeStartup;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlTopologyMapper.TimingSystemStartup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Loads the default IF-11 YAML document and coordinates schema mapping.
 *
 * <p>Template resolution and section-specific schema mapping are delegated to
 * focused package-private helpers. This class owns only file parsing, root
 * validation and assembly of the effective {@link Config}.</p>
 */
public final class YamlLoader {
    private static final String TIMING_SYSTEMS = "timingSystems";
    private static final String UPSTREAM = "upstream";
    private static final String PRESENTATION = "presentation";
    private static final String LOGGING = "logging";
    private static final String IO = "io";

    private YamlLoader() {
    }

    /** Loads one application YAML file into validated effective configuration. */
    public static Config load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException(
                    "config path must not be null");
        }

        Object document =
                loadDocument(
                        path);
        document =
                YamlTemplateResolver.resolve(
                        document);

        Map<?, ?> root =
                YamlValues.requireMapping(
                        document,
                        "configuration root");
        YamlValues.rejectUnknownFields(
                root,
                "configuration root",
                TIMING_SYSTEMS,
                UPSTREAM,
                PRESENTATION,
                LOGGING,
                IO);

        List<TimingSystemStartup> timingSystems =
                YamlTopologyMapper.map(
                        root.get(
                                TIMING_SYSTEMS));
        List<TimingNodeStartup> timingNodes =
                timingNodes(
                        timingSystems);
        Map<NodeId, Path> timingDataPaths =
                YamlTimingDataStorageMapper.map(
                        root.get(
                                IO),
                        timingNodes);

        List<Config.TimingSystemConfig> configuredSystems =
                configuredSystems(
                        timingSystems,
                        timingDataPaths);

        return new Config(
                configuredSystems,
                YamlAntennaMapper.map(
                        root.get(
                                IO)),
                YamlUpstreamMapper.map(
                        root.get(
                                UPSTREAM)),
                YamlPresentationMapper.map(
                        root.get(
                                PRESENTATION)),
                YamlLoggingMapper.map(
                        root.get(
                                LOGGING)),
                YamlLoggingMapper.mapLive(
                        root.get(
                                LOGGING)));
    }

    /** Parses the YAML document while rejecting duplicate mapping keys. */
    private static Object loadDocument(Path path) throws IOException {
        LoaderOptions options =
                new LoaderOptions();
        options.setAllowDuplicateKeys(
                false);
        Yaml yaml =
                new Yaml(
                        new SafeConstructor(
                                options));

        try (InputStream input =
                Files.newInputStream(
                        path)) {
            return yaml.load(
                    input);
        } catch (YAMLException ex) {
            throw new IllegalArgumentException(
                    "Invalid YAML configuration: "
                            + path,
                    ex);
        }
    }

    /** Flattens node startup values in configured system order. */
    private static List<TimingNodeStartup> timingNodes(
            List<TimingSystemStartup> timingSystems) {
        List<TimingNodeStartup> result =
                new ArrayList<TimingNodeStartup>();
        for (TimingSystemStartup timingSystem : timingSystems) {
            result.addAll(
                    timingSystem.timingNodes());
        }
        return result;
    }

    /** Combines mapped topology with the storage paths resolved from IO YAML. */
    private static List<Config.TimingSystemConfig> configuredSystems(
            List<TimingSystemStartup> timingSystems,
            Map<NodeId, Path> timingDataPaths) {
        List<Config.TimingSystemConfig> result =
                new ArrayList<Config.TimingSystemConfig>(
                        timingSystems.size());

        for (TimingSystemStartup timingSystem : timingSystems) {
            List<Config.TimingNodeConfig> configuredNodes =
                    new ArrayList<Config.TimingNodeConfig>(
                            timingSystem
                                    .timingNodes()
                                    .size());
            for (TimingNodeStartup timingNode
                    : timingSystem.timingNodes()) {
                configuredNodes.add(
                        new Config.TimingNodeConfig(
                                timingNode.timingNodeId(),
                                timingNode.antennaId(),
                                timingDataPaths.get(
                                        timingNode.timingNodeId()),
                                timingNode.tagProcessingPolicy()));
            }

            result.add(
                    new Config.TimingSystemConfig(
                            timingSystem.timingSystemId(),
                            configuredNodes,
                            timingSystem.eventDataProviderId(),
                            timingSystem.timingDataProviderId()));
        }

        return result;
    }
}
