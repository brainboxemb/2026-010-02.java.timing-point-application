package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;

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
                binding(
                        new NodeId("A"));

        AntennaManagerConfigRegistry registry =
                new AntennaManagerConfigRegistry(
                        Collections.singletonList(
                                binding),
                        systems);

        assertSame(
                binding,
                registry.binding(
                        "system-A"));
        assertSame(
                binding,
                registry.bindings().get(0));
    }

    @Test
    public void rejectsTargetOutsideBoundTimingSystem() {
        try {
            new AntennaManagerConfigRegistry(
                    Collections.singletonList(
                            binding(
                                    new NodeId("B"))),
                    systems());
            fail("Expected out-of-system antenna target to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(
                    expected.getMessage().contains(
                            "routes to a TimingNode outside TimingSystem system-A: B"));
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
                        "system-A",
                        Collections.singletonList(
                                node),
                        Config.REFERENCE_PROVIDER_ID,
                        Config.REFERENCE_PROVIDER_ID);
        return new TimingSystemConfigRegistry(
                Collections.singletonList(
                        system));
    }

    private static AntennaManagerConfig binding(
            NodeId target) {
        return new AntennaManagerConfig(
                "system-A",
                Collections.singletonList(
                        new AntennaManagerConfig.AntennaConfig(
                                new AntennaId("1"),
                                "simulated",
                                Collections.singletonList(
                                        target))),
                Collections.<AntennaId>emptyList(),
                null);
    }
}
