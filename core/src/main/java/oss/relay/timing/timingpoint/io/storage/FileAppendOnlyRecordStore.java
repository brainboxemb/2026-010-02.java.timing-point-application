package oss.relay.timing.timingpoint.io.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Append-only line-framed file store for opaque record payloads.
 *
 * <p>The store owns only filesystem mechanics and physical record framing:
 * LF output, CRLF-compatible recovery, incomplete-tail repair and durable
 * append through {@link FileChannel#force(boolean)}. Record meaning belongs to
 * the caller.</p>
 */
public final class FileAppendOnlyRecordStore
        implements AppendOnlyRecordStore, AppendOnlyRecordStoreMaintenance {
    private static final byte LF = (byte) '\n';
    private static final byte CR = (byte) '\r';

    private final Path file;

    public FileAppendOnlyRecordStore(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
    }

    @Override
    public synchronized LoadResult load() throws StoreException {
        if (!Files.exists(file)) {
            return new LoadResult(Collections.emptyList(), false);
        }

        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new StoreException("could not read record file " + file, ex);
        }

        if (bytes.length == 0) {
            return new LoadResult(Collections.emptyList(), false);
        }

        int completeLength = lastCompleteBoundary(bytes);
        boolean incompleteTail = completeLength < bytes.length;
        List<Record> records = decodeCompleteRecords(bytes, completeLength);

        if (incompleteTail) {
            truncateToCompleteBoundary(completeLength);
        }

        return new LoadResult(records, incompleteTail);
    }

    @Override
    public synchronized void append(byte[] record) throws StoreException {
        rejectInvalidRecord(record);

        byte[] framed = Arrays.copyOf(record, record.length + 1);
        framed[framed.length - 1] = LF;

        ensureParentDirectory();

        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(framed);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException ex) {
            throw new StoreException("could not durably append record to " + file, ex);
        }
    }

    @Override
    public synchronized Path backup() throws StoreException {
        try {
            return FileRecordStoreMaintenance.backup(file);
        } catch (IOException ex) {
            throw new StoreException(
                    "could not back up record file " + file,
                    ex);
        }
    }

    @Override
    public synchronized Path requestClearOnRestart()
            throws StoreException {
        try {
            return FileRecordStoreMaintenance.requestClearOnRestart(file);
        } catch (IOException ex) {
            throw new StoreException(
                    "could not schedule record-file clear " + file,
                    ex);
        }
    }

    private List<Record> decodeCompleteRecords(byte[] bytes, int completeLength)
            throws StoreException {
        if (completeLength == 0) {
            return Collections.emptyList();
        }

        List<Record> records = new ArrayList<>();
        int start = 0;
        int lineNumber = 1;

        for (int i = 0; i < completeLength; i++) {
            if (bytes[i] != LF) {
                continue;
            }

            int end = i;
            if (end > start && bytes[end - 1] == CR) {
                end--;
            }
            if (end == start) {
                throw new StoreException(
                        "blank record at line " + lineNumber);
            }
            for (int p = start; p < end; p++) {
                if (bytes[p] == CR) {
                    throw new StoreException(
                            "unexpected CR inside record at line " + lineNumber);
                }
            }

            records.add(new Record(Arrays.copyOfRange(bytes, start, end)));
            start = i + 1;
            lineNumber++;
        }

        return records;
    }

    private void ensureParentDirectory() throws StoreException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException ex) {
            throw new StoreException(
                    "could not create record directory " + parent,
                    ex);
        }
    }

    private void truncateToCompleteBoundary(int completeLength)
            throws StoreException {
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.WRITE)) {
            channel.truncate(completeLength);
            channel.force(true);
        } catch (IOException ex) {
            throw new StoreException(
                    "could not truncate incomplete record tail in " + file,
                    ex);
        }
    }

    private static int lastCompleteBoundary(byte[] bytes) {
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == LF) {
                return i + 1;
            }
        }
        return 0;
    }

    private static void rejectInvalidRecord(byte[] record)
            throws StoreException {
        if (record == null || record.length == 0) {
            throw new StoreException("record payload must not be empty");
        }
        for (byte value : record) {
            if (value == LF || value == CR) {
                throw new StoreException(
                        "record payload must contain exactly one physical line");
            }
        }
    }
}
