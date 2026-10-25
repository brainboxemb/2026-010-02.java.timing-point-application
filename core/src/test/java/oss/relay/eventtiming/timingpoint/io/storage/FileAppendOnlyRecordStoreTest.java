package oss.relay.eventtiming.timingpoint.io.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FileAppendOnlyRecordStoreTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendWritesLfTerminatedRecordAndLoadsIt() throws Exception {
        Path file = file();
        FileAppendOnlyRecordStore store = new FileAppendOnlyRecordStore(file);

        store.append("first".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(
                "first\n".getBytes(StandardCharsets.UTF_8),
                Files.readAllBytes(file));

        AppendOnlyRecordStore.LoadResult load = store.load();
        assertFalse(load.repairedIncompleteTail());
        assertEquals(1, load.records().size());
        assertArrayEquals(
                "first".getBytes(StandardCharsets.UTF_8),
                load.records().get(0).payload());
    }

    @Test
    public void appendCreatesMissingParentDirectory() throws Exception {
        Path file = temporaryFolder.getRoot().toPath()
                .resolve("nested")
                .resolve("records.txt");

        new FileAppendOnlyRecordStore(file)
                .append("first".getBytes(StandardCharsets.UTF_8));

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    public void loadAcceptsCrLfFraming() throws Exception {
        Path file = file();
        Files.write(
                file,
                "first\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8));

        AppendOnlyRecordStore.LoadResult load =
                new FileAppendOnlyRecordStore(file).load();

        assertFalse(load.repairedIncompleteTail());
        assertEquals(2, load.records().size());
        assertArrayEquals(
                "second".getBytes(StandardCharsets.UTF_8),
                load.records().get(1).payload());
    }

    @Test
    public void loadTruncatesOnlyIncompleteTrailingRecord() throws Exception {
        Path file = file();
        byte[] complete = "first\n".getBytes(StandardCharsets.UTF_8);
        byte[] incomplete = "second".getBytes(StandardCharsets.UTF_8);
        Files.write(file, concat(complete, incomplete));

        AppendOnlyRecordStore.LoadResult load =
                new FileAppendOnlyRecordStore(file).load();

        assertTrue(load.repairedIncompleteTail());
        assertEquals(1, load.records().size());
        assertArrayEquals(complete, Files.readAllBytes(file));
    }

    @Test
    public void loadTreatsCompletelyUnterminatedFileAsIncompleteTail() throws Exception {
        Path file = file();
        Files.write(file, "first".getBytes(StandardCharsets.UTF_8));

        AppendOnlyRecordStore.LoadResult load =
                new FileAppendOnlyRecordStore(file).load();

        assertTrue(load.repairedIncompleteTail());
        assertTrue(load.records().isEmpty());
        assertEquals(0L, Files.size(file));
    }

    @Test
    public void blankCompleteLineIsInvalid() throws Exception {
        Path file = file();
        Files.write(file, new byte[] {'\n'});

        try {
            new FileAppendOnlyRecordStore(file).load();
            fail("expected blank record failure");
        } catch (AppendOnlyRecordStore.StoreException expected) {
            assertTrue(expected.getMessage().contains("blank record"));
        }
    }

    @Test
    public void appendRejectsEmbeddedLineEnding() throws Exception {
        try {
            new FileAppendOnlyRecordStore(file())
                    .append("bad\nrecord".getBytes(StandardCharsets.UTF_8));
            fail("expected physical record rejection");
        } catch (AppendOnlyRecordStore.StoreException expected) {
            assertTrue(expected.getMessage().contains("one physical line"));
        }
    }

    private Path file() {
        return temporaryFolder.getRoot().toPath().resolve("records.txt");
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
