package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes.AntennaOperation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfig;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Composition tests: I/O is scoped per system, without a Domain-wide I/O facade. */
public class TimingSystemCompositionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void antennaControlRemainsScopedToItsTimingSystem() throws Exception {
        List<Config.TimingSystemConfig> systems = new ArrayList<Config.TimingSystemConfig>();
        List<AntennaManagerConfig> bindings = new ArrayList<AntennaManagerConfig>();
        for (String nodeId : new String[]{"A", "B"}) {
            systems.add(new Config.TimingSystemConfig(
                    "system-" + nodeId,
                    Collections.singletonList(node(nodeId)),
                    Config.REFERENCE_PROVIDER_ID,
                    Config.REFERENCE_PROVIDER_ID));
            bindings.add(new AntennaManagerConfig(
                    "system-" + nodeId,
                    Collections.singletonList(new AntennaManagerConfig.AntennaConfig(
                            new AntennaId("1"),
                            "simulated",
                            Collections.singletonList(new NodeId(nodeId)))),
                    Collections.<AntennaId>emptyList(),
                    null));
        }

        Config config = new Config(
                systems, bindings, new Presentation(null, null), null, null);
        TimingApplicationRuntime application =
                TimingApplicationRuntime.create(identity(), config);
        AntennaManager first = application.antennaManagers().get(0);
        AntennaManager second = application.antennaManagers().get(1);
        assertNotSame(first, second);

        application.activate();
        try {
            await(first::isReady);
            await(second::isReady);
            application.presentationGateway().timingNode(new NodeId("A"))
                    .open(new LocationId(24));
            await(() -> first.status(new AntennaId("1")).operation()
                    == AntennaOperation.INVENTORY);
            assertEquals(AntennaOperation.INACTIVE,
                    second.status(new AntennaId("1")).operation());

            application.presentationGateway().timingNode(new NodeId("A")).close();
            await(() -> first.status(new AntennaId("1")).operation()
                    == AntennaOperation.INACTIVE);

            application.presentationGateway().timingNode(new NodeId("B"))
                    .open(new LocationId(24));
            await(() -> second.status(new AntennaId("1")).operation()
                    == AntennaOperation.INVENTORY);
            assertEquals(AntennaOperation.INACTIVE,
                    first.status(new AntennaId("1")).operation());
        } finally {
            application.deactivate();
        }
    }

    @Test
    public void rejectsImplicitRoutingForMultipleNodes() {
        Config config = new Config(
                Arrays.asList(node("A"), node("B")),
                new Presentation(null, null),
                null,
                null,
                Config.REFERENCE_PROVIDER_ID,
                Config.REFERENCE_PROVIDER_ID);

        AntennaSet antennaSet = new AntennaSet()
                .add(new AntennaId("1"), new SimulatedAntenna());
        try {
            TimingApplicationRuntime.createSimulation(
                    identity(), config, antennaSet, EventData.empty());
            fail("Expected ambiguous implicit antenna routing to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(
                    "Implicit antenna routing requires exactly one TimingNode"));
        }
    }

    private Config.TimingNodeConfig node(String id) {
        Path path = temporaryFolder.getRoot().toPath()
                .resolve("node_" + id + "_logbook.jsonl");
        return new Config.TimingNodeConfig(
                new NodeId(id), path, TagProcessingPolicy.defaults());
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app", "test-version", "abc123def456",
                "feature/test", "local", false);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("condition did not become true before timeout");
            }
            Thread.sleep(5L);
        }
    }
}
