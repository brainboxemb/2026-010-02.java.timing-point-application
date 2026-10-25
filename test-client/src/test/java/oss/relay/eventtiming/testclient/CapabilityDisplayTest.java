package oss.relay.eventtiming.testclient;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityDisplayTest {
    @Test void unknownIsNotPresentedAsDisabled() {
        assertEquals("UNKNOWN — sync failed or in progress", CapabilityDisplay.format(null));
    }

    @Test void exposesAllSourceCapabilitiesIncludingFutureIds() {
        ApiClient.CapabilitiesResult result = new ApiClient.CapabilitiesResult(
                List.of(
                    new ApiClient.CapabilityInfo("DIRECT_REGISTRATION_SIMULATION", true, true),
                    new ApiClient.CapabilityInfo("TAG_SCENARIO_SIMULATION", true, false),
                    new ApiClient.CapabilityInfo("FUTURE_CAPABILITY", false, false)),
                "{}");
        assertEquals(
                "DIRECT_REGISTRATION_SIMULATION   yes / yes\n"
                + "TAG_SCENARIO_SIMULATION   yes / no\n"
                + "FUTURE_CAPABILITY   no / no",
                CapabilityDisplay.format(result));
    }

    @Test void emptySnapshotIsExplicit() {
        assertEquals("No capabilities advertised",
                CapabilityDisplay.format(new ApiClient.CapabilitiesResult(List.of(), "{}")));
    }
}
