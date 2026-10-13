package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StartupParametersTest {
    @Test
    public void logsChosenConfigurationAndEffectiveProviderIdentifiers() {
        Path configPath = Paths.get("config", "development-simulation.yml");
        Config config = new Config(new NodeId("A"), new Presentation(null, null));
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
    }
}
