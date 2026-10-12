package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class StartupParametersTest {
    @Test
    public void logsChosenConfigurationAndEffectiveProviderIdentifiers() {
        Path configPath = Paths.get("config", "development-simulation.yml");
        Config config = new Config(new NodeId("A"), new Presentation(null, null));
        String line = ApplicationLauncher.startupParametersLine(configPath, config);
        assertTrue(line.contains("action=START"));
        assertTrue(line.contains(configPath.toAbsolutePath().normalize().toString()));
        assertTrue(line.contains("SystemId=A"));
        assertTrue(line.contains("NodeIds=A"));
        assertTrue(line.contains("EventDataProvider=reference"));
        assertTrue(line.contains("TimingDataProvider=reference"));
    }
}
