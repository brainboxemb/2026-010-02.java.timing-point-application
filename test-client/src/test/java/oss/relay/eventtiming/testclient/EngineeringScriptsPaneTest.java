package oss.relay.eventtiming.testclient;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineeringScriptsPaneTest {
    @Test void localLuaCanRunWhileSimulationCapabilityIsUnavailable() {
        // Run readiness does not depend on SI-01 TAG_SCENARIO_SIMULATION.
        assertTrue(EngineeringScriptsPane.canRun(true, "A", false));
    }

    @Test void runRequiresSyncedSelectedNodeAndNoConcurrentGuiBatch() {
        assertFalse(EngineeringScriptsPane.canRun(false, "A", false));
        assertFalse(EngineeringScriptsPane.canRun(true, null, false));
        assertFalse(EngineeringScriptsPane.canRun(true, "", false));
        assertFalse(EngineeringScriptsPane.canRun(true, "A", true));
    }
}
