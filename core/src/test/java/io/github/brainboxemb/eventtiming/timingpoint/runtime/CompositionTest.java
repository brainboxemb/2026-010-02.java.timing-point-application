package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.AutomaticRegistration;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.DecryptedTagId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
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

public class CompositionTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void composesConfiguredTimingNodeIntoRuntime() {
        Config config = config(
                temporaryFolder.getRoot().toPath().resolve("timing-data.jsonl"));

        Application application = Composition.create(identity(), config);
        application.start();
        try {
            assertEquals(
                    "configured-node",
                    application.presentationGateway().timingNode().status().timingNodeId().value());
        } finally {
            application.close();
        }
    }

    @Test
    public void composedApplicationContainsTimingDataRecoveryFailureAsNodeError()
            throws Exception {
        Path file = temporaryFolder.getRoot().toPath().resolve("timing-data.jsonl");
        Files.write(
                file,
                "{not-json}\n".getBytes(StandardCharsets.UTF_8));

        Application application = Composition.create(identity(), config(file));

        application.start();
        try {
            assertEquals(Lifecycle.State.RUNNING, application.state());
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
            application.close();
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
        Composition.AntennaProcessing antennaProcessing =
                new Composition.AntennaProcessing(
                        Collections.singletonList(antenna),
                        tagId -> new RegistrationId("R-1001"));

        Application application = Composition.create(
                identity(),
                config(file, tagProcessingPolicy),
                antennaProcessing);

        assertEquals(
                tagProcessingPolicy,
                application.configuration()
                        .timingNode(new NodeId("configured-node"))
                        .tagProcessing()
                        .startupValue());

        application.start();
        try {
            assertTrue(antenna.inventoryRunning());
            application.timingNode().invoke(
                    TimingNodeCommands.open(new LocationId(24)));

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
                    new DecryptedTagId("TAG-1001"),
                    -42,
                    observedAt);

            assertTrue(
                    "quiet-timeout housekeeping did not commit the passage",
                    committed.await(2L, TimeUnit.SECONDS));
            assertEquals(
                    new RegistrationId("R-1001"),
                    automatic.get().registrationId());
            assertEquals(observedAt, automatic.get().effectiveTime());
        } finally {
            application.close();
        }

        assertFalse(antenna.inventoryRunning());
        try {
            antenna.emit(
                    new DecryptedTagId("TAG-1002"),
                    -40,
                    TimingTimestamp.parse("2026-10-05T08:30:01.000000000Z"));
            fail("expected closed antenna after application shutdown");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("closed"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void createRejectsMissingTimingDataPath() {
        Config config = new Config(
                new NodeId("configured-node"),
                new Presentation(null, null));

        Composition.create(identity(), config);
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
