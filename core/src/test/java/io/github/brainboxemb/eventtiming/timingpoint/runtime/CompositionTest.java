package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    @Test(expected = IllegalArgumentException.class)
    public void createRejectsMissingTimingDataPath() {
        Config config = new Config(
                new NodeId("configured-node"),
                new Presentation(null, null));

        Composition.create(identity(), config);
    }

    private static Config config(Path timingDataPath) {
        return new Config(
                new NodeId("configured-node"),
                new Presentation(null, null),
                null,
                null,
                timingDataPath);
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
