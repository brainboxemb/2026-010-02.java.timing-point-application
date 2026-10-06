package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TagId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TimingApplicationRuntimeTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void composesConfiguredTimingNodeIntoRuntime() {
        Config config = config(
                temporaryFolder.getRoot().toPath().resolve("timing-data.jsonl"));

        TimingApplicationRuntime application = TimingApplicationRuntime.create(identity(), config);
        application.activate();
        try {
            assertEquals(
                    "configured-node",
                    application.presentationGateway().timingNode().status().timingNodeId().value());
        } finally {
            application.deactivate();
        }
    }

    @Test
    public void composedApplicationContainsTimingDataRecoveryFailureAsNodeError()
            throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("timing-data.jsonl");
        Files.write(
                file,
                "{not-json}\n".getBytes(StandardCharsets.UTF_8));

        TimingApplicationRuntime application = TimingApplicationRuntime.create(identity(), config(file));

        application.activate();
        try {
            assertEquals(TimingApplicationRuntime.State.ACTIVE, application.state());
            assertEquals(
                    TimingNodeTypes.Lifecycle.ERROR,
                    application.presentationGateway().timingNode().status().lifecycle());
            assertEquals(
                    1,
                    application.presentationGateway().timingNode().status().problems().size());
            assertEquals(
                    TimingNodeTypes.ProblemCode.TIMING_DATA_RECOVERY_FAILED,
                    application.presentationGateway().timingNode().status().problems().get(0).code());
            assertTrue(
                    application.presentationGateway().timingNode().status().problems().get(0).message()
                            .contains("TimingData recovery failed"));
        } finally {
            application.deactivate();
        }
    }

    @Test
    public void composesAntennaPathAndCommitsQuietExpiredPassage()
            throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("antenna-timing-data.jsonl");
        SimulatedAntenna antenna = new SimulatedAntenna();
        TagProcessingPolicy tagProcessingPolicy =
                new TagProcessingPolicy(
                        Duration.ofMillis(20),
                        Duration.ofMillis(250),
                        Duration.ofMillis(100),
                        Duration.ofMillis(5),
                        8);
        TimingApplicationRuntime application = SimulationRuntime.create(
                identity(),
                config(file, tagProcessingPolicy),
                Collections.singletonList(
                        AntennaInstallation.direct(
                                new AntennaId("ANT1"),
                                antenna)),
                eventData(
                        "TAG-1001", "R-1001",
                        "TAG-1001-B", "R-1001"));

        assertEquals(
                tagProcessingPolicy,
                application.configuration()
                        .timingNode(new NodeId("configured-node"))
                        .tagProcessing()
                        .startupValue());

        application.activate();
        try {
            assertFalse(antenna.inventoryRunning());

            application.timingNode().invoke(
                    TimingNodeCommands.open(new LocationId(24)));
            await(antenna::inventoryRunning, 1000L);

            CountDownLatch committed = new CountDownLatch(1);
            AtomicReference<AutomaticRegistration> automatic =
                    new AtomicReference<>();
            application.timingNode().timingDataCommittedEvent().subscribe(data -> {
                if (data instanceof AutomaticRegistration) {
                    automatic.set((AutomaticRegistration) data);
                    committed.countDown();
                }
            });

            TimingTimestamp observedAt =
                    TimingTimestamp.parse("2026-10-05T08:30:00.000000000Z");
            antenna.emit(
                    new TagId("TAG-1001"),
                    -42,
                    observedAt);

            assertTrue(
                    "quiet-timeout housekeeping did not commit the passage",
                    committed.await(2L, TimeUnit.SECONDS));
            assertEquals(
                    new RegistrationId("R-1001"),
                    automatic.get().registrationId());
            assertEquals(observedAt, automatic.get().effectiveTime());

            application.timingNode().invoke(
                    TimingNodeCommands.close());
            await(() -> !antenna.inventoryRunning(), 1000L);
        } finally {
            application.deactivate();
        }

        assertFalse(antenna.inventoryRunning());
        try {
            antenna.emit(
                    new TagId("TAG-1002"),
                    -40,
                    TimingTimestamp.parse("2026-10-05T08:30:01.000000000Z"));
            fail("expected shut-down antenna after application shutdown");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("shut down"));
        }
    }

    private static void await(
            java.util.function.BooleanSupplier condition,
            long timeoutMillis)
            throws Exception {
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                timeoutMillis);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "condition did not become true before timeout");
            }
            Thread.sleep(5L);
        }
    }

    @Test
    public void formatsStableSmokeOutput() {
        assertEquals(
                "event-timing-app lifecycle OK version=test-version state=INACTIVE",
                TimingApplicationRuntime.smokeOutput(
                        identity(),
                        TimingApplicationRuntime.State.INACTIVE));
    }

    @Test(expected = IllegalArgumentException.class)
    public void createRejectsMissingBuildIdentity() {
        TimingApplicationRuntime.create(
                null,
                config(
                        temporaryFolder
                                .getRoot()
                                .toPath()
                                .resolve("timing-data.jsonl")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void createRejectsMissingTimingDataPath() {
        Config config = new Config(
                new NodeId("configured-node"),
                new Presentation(null, null));

        TimingApplicationRuntime.create(identity(), config);
    }

    private static Config config(Path timingDataPath) {
        return config(timingDataPath, TagProcessingPolicy.defaults());
    }

    private static Config config(
            Path timingDataPath,
            TagProcessingPolicy tagProcessingPolicy) {
        return new Config(
                new NodeId("configured-node"),
                new Presentation(null, null),
                null,
                null,
                timingDataPath,
                tagProcessingPolicy);
    }

    private static EventData eventData(
            String... tagAndRegistrationIds) {
        Map<TagId, RegistrationId> registrations =
                new LinkedHashMap<TagId, RegistrationId>();
        for (int index = 0;
                index < tagAndRegistrationIds.length;
                index += 2) {
            registrations.put(
                    new TagId(
                            tagAndRegistrationIds[index]),
                    new RegistrationId(
                            tagAndRegistrationIds[index + 1]));
        }
        return new EventData(
                registrations);
    }

    private static BuildIdentity identity() {
        return BuildIdentity.firstApiVersion(
                "event-timing-app",
                "test-version",
                "abc123def456",
                "feature/test",
                "local",
                false);
    }
}
