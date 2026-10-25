package oss.relay.eventtiming.timingpoint.app;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.eventtiming.timingpoint.runtime.config.Config;
import oss.relay.eventtiming.timingpoint.runtime.config.Presentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;

public class StartupParametersTest {
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
}
