package oss.relay.eventtiming.timingpoint.runtime.config;

import oss.relay.eventtiming.timingdata.TimingDataTypes.NodeId;
import oss.relay.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.AntennaId;

import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AntennaManagerConfigRegistryTest {
    @Test
    public void providesBindingByTimingSystemId() {
        TimingSystemConfigRegistry systems =
                systems();
        AntennaManagerConfig binding =
                binding("A");

        AntennaManagerConfigRegistry registry =
                new AntennaManagerConfigRegistry(
                        Collections.singletonList(
                                binding),
                        systems);

        assertSame(
                binding,
                registry.binding(
                        "A"));
        assertSame(
                binding,
                registry.bindings().get(0));
    }

    @Test
    public void rejectsUnknownTimingSystemBinding() {
        try {
            new AntennaManagerConfigRegistry(
                    Collections.singletonList(
                            binding("B")),
                    systems());
            fail("Expected unknown TimingSystem binding to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    expected.getMessage().contains(
                            "Unknown or duplicate AntennaManager TimingSystem binding"));
        }
    }

    private static TimingSystemConfigRegistry systems() {
        Config.TimingNodeConfig node =
                new Config.TimingNodeConfig(
                        new NodeId("A"),
                        null,
                        TagProcessingPolicy.defaults());
        Config.TimingSystemConfig system =
                new Config.TimingSystemConfig(
                        "A",
                        Collections.singletonList(
                                node),
                        Config.REFERENCE_PROVIDER_ID,
                        Config.REFERENCE_PROVIDER_ID);
        return new TimingSystemConfigRegistry(
                Collections.singletonList(
                        system));
    }

    private static AntennaManagerConfig binding(
            String timingSystemId) {
        return new AntennaManagerConfig(
                timingSystemId,
                Collections.singletonList(
                        new AntennaManagerConfig.AntennaConfig(
                                new AntennaId("1"),
                                "simulated")),
                Collections.<AntennaId>emptyList(),
                null);
    }
}
