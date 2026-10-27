package oss.relay.timing.timingpoint.app;

import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.timing.timingpoint.runtime.config.Config;
import oss.relay.timing.timingpoint.runtime.config.Presentation;
import oss.relay.timing.timingpoint.io.storage.FileRecordStoreMaintenance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class StartupParametersTest {
    @Rule
    public final TemporaryFolder temporaryFolder =
            new TemporaryFolder();

    @Test
    public void logsChosenConfigurationAndEffectiveProviderIdentifiers() {
        Path configPath = Paths.get("config", "development-simulation.yml");
        Config.TimingNodeConfig timingNode =
                new Config.TimingNodeConfig(
                        new NodeId("A"),
                        null,
                        TagProcessingPolicy.defaults());
        Config.TimingSystemConfig timingSystem =
                new Config.TimingSystemConfig(
                        "A",
                        Collections.singletonList(
                                timingNode),
                        Config.REFERENCE_PROVIDER_ID,
                        Config.REFERENCE_PROVIDER_ID);
        Config config =
                new Config(
                        Collections.singletonList(
                                timingSystem),
                        new Presentation(
                                null,
                                null),
                        null,
                        null);
        List<String> lines =
                ApplicationLauncher.startupConfigurationLines(
                        configPath,
                        config);

        assertEquals("Startup configuration", lines.get(0));
        assertEquals("  Action: START", lines.get(1));
        assertEquals(
                "  Config: " + configPath.toAbsolutePath().normalize(),
                lines.get(2));
        assertEquals("  SystemId: A", lines.get(3));
        assertEquals("    NodeId: A", lines.get(4));
        assertEquals("    EventDataProvider: reference", lines.get(5));
        assertEquals("    TimingDataProvider: reference", lines.get(6));
        assertEquals(7, lines.size());
    }

    @Test
    public void appliesPendingTimingDataClearBeforeRuntimeComposition()
            throws Exception {
        Path timingData =
                temporaryFolder.getRoot()
                        .toPath()
                        .resolve("timing-data.jsonl");
        byte[] original =
                "{\"seq\":1}\n".getBytes(
                        StandardCharsets.UTF_8);
        Files.write(
                timingData,
                original);

        Path reservedBackup =
                FileRecordStoreMaintenance.requestClearOnRestart(
                        timingData);

        Config.TimingNodeConfig timingNode =
                new Config.TimingNodeConfig(
                        new NodeId("A"),
                        timingData,
                        TagProcessingPolicy.defaults());
        Config.TimingSystemConfig timingSystem =
                new Config.TimingSystemConfig(
                        "A",
                        Collections.singletonList(
                                timingNode),
                        Config.REFERENCE_PROVIDER_ID,
                        Config.REFERENCE_PROVIDER_ID);
        Config config =
                new Config(
                        Collections.singletonList(
                                timingSystem),
                        new Presentation(
                                null,
                                null),
                        null,
                        null);

        List<Path> backups =
                ApplicationLauncher.applyPendingTimingDataMaintenance(
                        config);

        assertEquals(
                Collections.singletonList(reservedBackup),
                backups);
        assertArrayEquals(
                original,
                Files.readAllBytes(reservedBackup));
        assertFalse(
                Files.exists(timingData));
        assertFalse(
                FileRecordStoreMaintenance.hasPendingClear(
                        timingData));
        assertEquals(
                Collections.emptyList(),
                ApplicationLauncher.applyPendingTimingDataMaintenance(
                        config));
    }
}
