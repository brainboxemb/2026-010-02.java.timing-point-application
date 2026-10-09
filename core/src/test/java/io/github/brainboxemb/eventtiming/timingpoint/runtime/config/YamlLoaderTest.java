package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class YamlLoaderTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsSingleTimingNodeIdAndStorageWithoutPresentation()
            throws Exception {
        Config config = load(
                timingNode("A")
                        + timingDataStorage());

        assertEquals("A", config.timingNodeId().value());
        assertNull(config.presentation().remoteShell());
        assertNull(config.presentation().api());
        assertNull(config.logging());
        assertNull(config.loggingServer());
        assertEquals(
                Paths.get("data", "node_A_logbook.jsonl"),
                config.timingDataPath());
        assertEquals(
                TagProcessingPolicy.defaults(),
                config.tagProcessingPolicy());
        assertEquals(
                "reference",
                config.eventDataProviderId());
        assertEquals(
                "reference",
                config.timingDataProviderId());
    }

    @Test
    public void loadsExplicitTimingSystemProviderSelections()
            throws Exception {
        Config config = load(
                "timingSystems:\n"
                        + "  timing-system-01:\n"
                        + "    timingSystemId: timing-system-01\n"
                        + "    eventDataProvider: custom-event\n"
                        + "    timingDataProvider: custom-timing\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: A\n"
                        + timingDataStorage());

        assertEquals(
                "custom-event",
                config.eventDataProviderId());
        assertEquals(
                "custom-timing",
                config.timingDataProviderId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankEventDataProviderSelection()
            throws Exception {
        load(
                "timingSystems:\n"
                        + "  timing-system-01:\n"
                        + "    timingSystemId: timing-system-01\n"
                        + "    eventDataProvider: '   '\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: A\n"
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankTimingDataProviderSelection()
            throws Exception {
        load(
                "timingSystems:\n"
                        + "  timing-system-01:\n"
                        + "    timingSystemId: timing-system-01\n"
                        + "    timingDataProvider: '   '\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: A\n"
                        + timingDataStorage());
    }

    @Test
    public void loadsPartialTimingNodeTagProcessingOverride()
            throws Exception {
        Config config = load(
                timingNode(
                        "A",
                        "          quietTimeoutMillis: 300\n"
                                + "          duplicateWindowMillis: 0\n"
                                + "          observationQueueCapacity: 64\n")
                        + timingDataStorage());

        TagProcessingPolicy defaults =
                TagProcessingPolicy.defaults();
        TagProcessingPolicy policy =
                config.tagProcessingPolicy();

        assertEquals(
                TimeUnit.MILLISECONDS.toNanos(300L),
                policy.quietTimeoutNanos());
        assertEquals(
                defaults.maxBurstDurationNanos(),
                policy.maxBurstDurationNanos());
        assertEquals(
                0L,
                policy.duplicateWindowNanos());
        assertEquals(
                defaults.sweepCadenceNanos(),
                policy.sweepCadenceNanos());
        assertEquals(
                64,
                policy.observationQueueCapacity());
    }

    @Test
    public void loadsCompleteTimingNodeTagProcessingOverride()
            throws Exception {
        Config config = load(
                timingNode(
                        "A",
                        "          quietTimeoutMillis: 200\n"
                                + "          maxBurstDurationMillis: 900\n"
                                + "          duplicateWindowMillis: 12000\n"
                                + "          sweepCadenceMillis: 25\n"
                                + "          observationQueueCapacity: 128\n")
                        + timingDataStorage());

        TagProcessingPolicy policy =
                config.tagProcessingPolicy();
        assertEquals(
                TimeUnit.MILLISECONDS.toNanos(200L),
                policy.quietTimeoutNanos());
        assertEquals(
                TimeUnit.MILLISECONDS.toNanos(900L),
                policy.maxBurstDurationNanos());
        assertEquals(
                TimeUnit.MILLISECONDS.toNanos(12000L),
                policy.duplicateWindowNanos());
        assertEquals(
                TimeUnit.MILLISECONDS.toNanos(25L),
                policy.sweepCadenceNanos());
        assertEquals(
                128,
                policy.observationQueueCapacity());
    }

    @Test
    public void loadsImplementedPresentationConfig() throws Exception {
        Config config = load(
                timingNode("A")
                        + timingDataStorage()
                        + "presentation:\n"
                        + "  remoteShell:\n"
                        + "    bindAddress: 127.0.0.1\n"
                        + "    port: 8023\n"
                        + "  api:\n"
                        + "    http:\n"
                        + "      bindAddress: 127.0.0.1\n"
                        + "      port: 8081\n"
                        + "    webSocket:\n"
                        + "      bindAddress: 127.0.0.1\n"
                        + "      port: 8082\n");

        assertEquals(
                "127.0.0.1",
                config.presentation().remoteShell().bindAddress());
        assertEquals(
                8023,
                config.presentation().remoteShell().port());
        assertEquals(
                "127.0.0.1",
                config.presentation().api().http().bindAddress());
        assertEquals(
                8081,
                config.presentation().api().http().port());
        assertEquals(
                "127.0.0.1",
                config.presentation().api().webSocket().bindAddress());
        assertEquals(
                8082,
                config.presentation().api().webSocket().port());
    }

    @Test
    public void loadsRuntimeLoggingConfig() throws Exception {
        Config config = load(
                timingNode("A")
                        + timingDataStorage()
                        + "logging:\n"
                        + "  level: DEBUG\n"
                        + "  file:\n"
                        + "    path: logs\n"
                        + "    rotateBytes: 1048576\n"
                        + "    retainedFiles: 5\n"
                        + "  live:\n"
                        + "    bindAddress: 127.0.0.1\n"
                        + "    port: 8030\n");

        assertEquals(
                io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevel.DEBUG,
                config.logging().level());
        assertEquals("logs", config.logging().file().path());
        assertEquals(
                1048576,
                config.logging().file().rotateBytes());
        assertEquals(
                5,
                config.logging().file().retainedFiles());
        assertEquals(
                "127.0.0.1",
                config.loggingServer().bindAddress());
        assertEquals(
                8030,
                config.loggingServer().port());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedLoggingLevel() throws Exception {
        load(
                timingNode("A")
                        + timingDataStorage()
                        + "logging:\n"
                        + "  level: VERBOSE\n"
                        + "  file:\n"
                        + "    path: logs\n"
                        + "    rotateBytes: 1024\n"
                        + "    retainedFiles: 2\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimingDataStorage() throws Exception {
        load(timingNode("A"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankTimingDataPath() throws Exception {
        load(
                timingNode("A")
                        + "io:\n"
                        + "  storage:\n"
                        + "    timingData:\n"
                        + "      path: '   '\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimingSystems() throws Exception {
        load("{}\n");
    }

    @Test
    public void loadsMultipleTimingSystems()
            throws Exception {
        Config config =
                load(
                        "timingSystems:\n"
                                + "  timing-system-01:\n"
                                + "    timingSystemId: timing-system-01\n"
                                + "    timingNodes:\n"
                                + "      timing-node-01:\n"
                                + "        timingNodeId: A\n"
                                + "  timing-system-02:\n"
                                + "    timingSystemId: timing-system-02\n"
                                + "    timingNodes:\n"
                                + "      timing-node-02:\n"
                                + "        timingNodeId: B\n"
                                + timingDataStorageNodes());

        assertEquals(
                2,
                config.timingSystems().size());
        assertEquals(
                "timing-system-01",
                config.timingSystems()
                        .get(0)
                        .timingSystemId());
        assertEquals(
                "timing-system-02",
                config.timingSystems()
                        .get(1)
                        .timingSystemId());
        assertEquals(
                2,
                config.timingNodes().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateTimingSystemId()
            throws Exception {
        load(
                "timingSystems:\n"
                        + "  first:\n"
                        + "    timingSystemId: shared\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: A\n"
                        + "  second:\n"
                        + "    timingSystemId: shared\n"
                        + "    timingNodes:\n"
                        + "      timing-node-02:\n"
                        + "        timingNodeId: B\n"
                        + timingDataStorageNodes());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateTimingNodeIdAcrossSystems()
            throws Exception {
        load(
                "timingSystems:\n"
                        + "  first:\n"
                        + "    timingSystemId: first\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: A\n"
                        + "  second:\n"
                        + "    timingSystemId: second\n"
                        + "    timingNodes:\n"
                        + "      timing-node-02:\n"
                        + "        timingNodeId: A\n"
                        + "io:\n"
                        + "  storage:\n"
                        + "    timingData:\n"
                        + "      nodes:\n"
                        + "        node-a:\n"
                        + "          timingNodeId: A\n"
                        + "          path: data/node_A_logbook.jsonl\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimingSystemId()
            throws Exception {
        load(
                "timingSystems:\n"
                        + "  timing-system-01:\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: A\n"
                        + timingDataStorage());
    }

    @Test
    public void loadsMultipleTimingNodesWithPerNodeStorage()
            throws Exception {
        Config config =
                load(
                        twoTimingNodes()
                                + timingDataStorageNodes());

        assertEquals(
                2,
                config.timingNodes().size());
        assertEquals(
                Paths.get(
                        "data",
                        "node_A_logbook.jsonl"),
                config.timingNode(
                                new NodeId("A"))
                        .timingDataPath());
        assertEquals(
                Paths.get(
                        "data",
                        "node_B_logbook.jsonl"),
                config.timingNode(
                                new NodeId("B"))
                        .timingDataPath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSingleStoragePathForMultipleTimingNodes()
            throws Exception {
        load(
                twoTimingNodes()
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingPerNodeStorageBinding()
            throws Exception {
        load(
                twoTimingNodes()
                        + "io:\n"
                        + "  storage:\n"
                        + "    timingData:\n"
                        + "      nodes:\n"
                        + "        node-a:\n"
                        + "          timingNodeId: A\n"
                        + "          path: data/node_A_logbook.jsonl\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownPerNodeStorageBinding()
            throws Exception {
        load(
                twoTimingNodes()
                        + "io:\n"
                        + "  storage:\n"
                        + "    timingData:\n"
                        + "      nodes:\n"
                        + "        node-a:\n"
                        + "          timingNodeId: A\n"
                        + "          path: data/node_A_logbook.jsonl\n"
                        + "        node-c:\n"
                        + "          timingNodeId: C\n"
                        + "          path: data/node_C_logbook.jsonl\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicatePerNodeStoragePath()
            throws Exception {
        load(
                twoTimingNodes()
                        + "io:\n"
                        + "  storage:\n"
                        + "    timingData:\n"
                        + "      nodes:\n"
                        + "        node-a:\n"
                        + "          timingNodeId: A\n"
                        + "          path: data/shared-logbook.jsonl\n"
                        + "        node-b:\n"
                        + "          timingNodeId: B\n"
                        + "          path: data/shared-logbook.jsonl\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingTimingNodeId() throws Exception {
        load(
                "timingSystems:\n"
                        + "  timing-system-01:\n"
                        + "    timingSystemId: timing-system-01\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01: {}\n"
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankTimingNodeId() throws Exception {
        load(
                "timingSystems:\n"
                        + "  timing-system-01:\n"
                        + "    timingSystemId: timing-system-01\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: '   '\n"
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRootTagProcessingShortcut() throws Exception {
        load(
                timingNode("A")
                        + "tagProcessing:\n"
                        + "  quietTimeoutMillis: 200\n"
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyTagProcessingSection() throws Exception {
        load(
                timingNode("A")
                        + "        tagProcessing:\n"
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownTagProcessingField() throws Exception {
        load(
                timingNode(
                        "A",
                        "          batchSize: 8\n")
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroQuietTimeout() throws Exception {
        load(
                timingNode(
                        "A",
                        "          quietTimeoutMillis: 0\n")
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeDuplicateWindow() throws Exception {
        load(
                timingNode(
                        "A",
                        "          duplicateWindowMillis: -1\n")
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroObservationQueueCapacity() throws Exception {
        load(
                timingNode(
                        "A",
                        "          observationQueueCapacity: 0\n")
                        + timingDataStorage());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownRootField() throws Exception {
        load(
                timingNode("A")
                        + timingDataStorage()
                        + "unknown: true\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownHttpField() throws Exception {
        load(
                timingNode("A")
                        + timingDataStorage()
                        + "presentation:\n"
                        + "  api:\n"
                        + "    http:\n"
                        + "      bindAddress: 127.0.0.1\n"
                        + "      port: 8081\n"
                        + "      protocol: https\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingHttpBindAddress() throws Exception {
        load(
                timingNode("A")
                        + timingDataStorage()
                        + "presentation:\n"
                        + "  api:\n"
                        + "    http:\n"
                        + "      port: 8081\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidHttpPort() throws Exception {
        load(
                timingNode("A")
                        + timingDataStorage()
                        + "presentation:\n"
                        + "  api:\n"
                        + "    http:\n"
                        + "      bindAddress: 127.0.0.1\n"
                        + "      port: 70000\n");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyApi() throws Exception {
        load(
                timingNode("A")
                        + timingDataStorage()
                        + "presentation:\n"
                        + "  api: {}\n");
    }

    @Test
    public void loadsAntennaRoutingAndInventoryGroup() throws Exception {
        Config config = load(twoTimingNodes()
                + timingDataStorageNodes()
                + "  devices:\n"
                + "    antennaManagers:\n"
                + "      primary:\n"
                + "        timingSystemId: timing-system-01\n"
                + "        antennas:\n"
                + "          1:\n"
                + "            provider: simulated\n"
                + "            type: rfid\n"
                + "            timingNodes: [A, B]\n"
                + "          2:\n"
                + "            provider: simulated\n"
                + "            type: rfid\n"
                + "            timingNodes: [B]\n"
                + "        inventoryGroup:\n"
                + "          members: [1, 2]\n"
                + "          intervalMillis: 500\n");
        assertEquals(1, config.antennaManagers().size());
        assertEquals(2, config.antennaManagers().get(0).antennas().size());
        assertEquals(2, config.antennaManagers().get(0).antennas().get(0).timingNodes().size());
        assertEquals(500L, config.antennaManagers().get(0).inventoryInterval().toMillis());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCrossSystemAntennaRoute() throws Exception {
        load(timingNode("A") + timingDataStorage()
                + "  devices:\n"
                + "    antennaManagers:\n"
                + "      primary:\n"
                + "        timingSystemId: timing-system-01\n"
                + "        antennas:\n"
                + "          1:\n"
                + "            provider: simulated\n"
                + "            type: rfid\n"
                + "            timingNodes: [B]\n");
    }

    private static String twoTimingNodes() {
        return "timingSystems:\n"
                + "  timing-system-01:\n"
                        + "    timingSystemId: timing-system-01\n"
                + "    timingNodes:\n"
                + "      timing-node-01:\n"
                + "        timingNodeId: A\n"
                + "      timing-node-02:\n"
                + "        timingNodeId: B\n";
    }

    private static String timingDataStorageNodes() {
        return "io:\n"
                + "  storage:\n"
                + "    timingData:\n"
                + "      nodes:\n"
                + "        node-a:\n"
                + "          timingNodeId: A\n"
                + "          path: data/node_A_logbook.jsonl\n"
                + "        node-b:\n"
                + "          timingNodeId: B\n"
                + "          path: data/node_B_logbook.jsonl\n";
    }

    private static String timingNode(String nodeId) {
        return timingNode(nodeId, null);
    }

    private static String timingNode(
            String nodeId,
            String tagProcessingFields) {
        String yaml =
                "timingSystems:\n"
                        + "  timing-system-01:\n"
                        + "    timingSystemId: timing-system-01\n"
                        + "    timingNodes:\n"
                        + "      timing-node-01:\n"
                        + "        timingNodeId: " + nodeId + "\n";
        if (tagProcessingFields != null) {
            yaml +=
                    "        tagProcessing:\n"
                            + tagProcessingFields;
        }
        return yaml;
    }

    private static String timingDataStorage() {
        return "io:\n"
                + "  storage:\n"
                + "    timingData:\n"
                + "      path: data/node_A_logbook.jsonl\n";
    }

    private Config load(String yaml) throws Exception {
        File file = temporaryFolder.newFile("application.yml");
        Files.write(
                file.toPath(),
                yaml.getBytes(StandardCharsets.UTF_8));
        return YamlLoader.load(file.toPath());
    }
}
