package io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DefaultTimingDataPersistenceTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final TimingTimestamp EFFECTIVE =
            TimingTimestamp.parse("2026-10-01T12:00:00.000000000Z");
    private static final TimingTimestamp RECORDED =
            TimingTimestamp.parse("2026-10-01T12:00:01.000000000Z");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final DefaultTimingDataFactory factory = new DefaultTimingDataFactory();
    private final DefaultTimingDataCodec codec = new DefaultTimingDataCodec();

    @Test
    public void appendAndLoadRoundTripsTimingData() throws Exception {
        TimingDataPersistence persistence = persistence(file());
        TimingData first = data(NODE_ID, 1L);

        persistence.append(first);

        TimingDataPersistence.LoadResult load = persistence.load();
        assertFalse(load.repairedIncompleteTail());
        assertEquals(1, load.records().size());
        TimingData loaded = load.records().get(0);
        assertEquals(first.timingNodeId(), loaded.timingNodeId());
        assertEquals(first.sequenceNumber(), loaded.sequenceNumber());
        assertEquals(first.locationId(), loaded.locationId());
        assertEquals(first.effectiveTime(), loaded.effectiveTime());
        assertEquals(first.recordedAt(), loaded.recordedAt());
    }

    @Test
    public void corruptCompleteRecordStopsRecovery() throws Exception {
        Path file = file();
        Files.write(file, "{not-json}\n".getBytes(StandardCharsets.UTF_8));

        assertLoadFails(file, "record 1");
    }

    @Test
    public void sequenceGapStopsRecovery() throws Exception {
        Path file = file();
        Files.write(
                file,
                concat(
                        codec.encode(data(NODE_ID, 1L)),
                        new byte[] {'\n'},
                        codec.encode(data(NODE_ID, 3L)),
                        new byte[] {'\n'}));

        assertLoadFails(file, "sequence must be 2");
    }

    @Test
    public void duplicateSequenceStopsRecovery() throws Exception {
        Path file = file();
        Files.write(
                file,
                concat(
                        codec.encode(data(NODE_ID, 1L)),
                        new byte[] {'\n'},
                        codec.encode(data(NODE_ID, 1L)),
                        new byte[] {'\n'}));

        assertLoadFails(file, "sequence must be 2");
    }

    @Test
    public void regressedSequenceStopsRecovery() throws Exception {
        Path file = file();
        Files.write(
                file,
                concat(
                        codec.encode(data(NODE_ID, 1L)),
                        new byte[] {'\n'},
                        codec.encode(data(NODE_ID, 2L)),
                        new byte[] {'\n'},
                        codec.encode(data(NODE_ID, 1L)),
                        new byte[] {'\n'}));

        assertLoadFails(file, "sequence must be 3");
    }

    @Test
    public void differentTimingNodeStopsRecovery() throws Exception {
        Path file = file();
        Files.write(
                file,
                concat(
                        codec.encode(data(new NodeId("B"), 1L)),
                        new byte[] {'\n'}));

        assertLoadFails(file, "but persistence owns " + NODE_ID);
    }

    @Test
    public void appendRejectsDifferentTimingNode() throws Exception {
        TimingDataPersistence persistence = persistence(file());

        try {
            persistence.append(data(new NodeId("B"), 1L));
            fail("expected wrong TimingNode rejection");
        } catch (TimingDataPersistence.PersistenceException expected) {
            assertTrue(expected.getMessage().contains("but persistence owns " + NODE_ID));
        }
    }

    @Test
    public void physicalTailRepairIsReportedThroughTimingDataResult() throws Exception {
        Path file = file();
        byte[] first = codec.encode(data(NODE_ID, 1L));
        byte[] second = codec.encode(data(NODE_ID, 2L));
        Files.write(
                file,
                concat(
                        first,
                        new byte[] {'\n'},
                        java.util.Arrays.copyOf(second, second.length - 5)));

        TimingDataPersistence.LoadResult load = persistence(file).load();

        assertTrue(load.repairedIncompleteTail());
        assertEquals(1, load.records().size());
    }

    private TimingDataPersistence persistence(Path file) {
        return new DefaultTimingDataPersistence(
                new FileAppendOnlyRecordStore(file),
                NODE_ID,
                codec);
    }

    private Path file() {
        return temporaryFolder.getRoot().toPath().resolve("timing-data.jsonl");
    }

    private TimingData data(NodeId nodeId, long sequence) {
        return factory.createManualRegistration(
                new TimingDataFactory.Context(
                        nodeId,
                        sequence,
                        new LocationId(24),
                        EFFECTIVE,
                        RECORDED),
                new RegistrationId("registration-" + sequence),
                TimingData.ManualTimeSource.AUTOMATIC);
    }

    private void assertLoadFails(Path file, String expectedMessage) throws Exception {
        try {
            persistence(file).load();
            fail("expected recovery failure");
        } catch (TimingDataPersistence.PersistenceException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(expectedMessage));
        }
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
