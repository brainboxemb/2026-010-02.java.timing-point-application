package oss.relay.eventtiming.testclient;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineeringScriptsStarterTest {
    @Test
    void starterRunsLocallyWithoutSimulationCapability() {
        List<String> messages = new ArrayList<>();
        EngineeringLuaEngine.run(
                EngineeringScriptsPane.STARTER,
                (regId, profile) -> fail("Starter must not request simulated tags"),
                messages::add,
                () -> true);
        assertTrue(messages.contains("Engineering Lua is ready"));
        assertTrue(messages.contains("Selected TimingNode is LIVE"));
    }
}
