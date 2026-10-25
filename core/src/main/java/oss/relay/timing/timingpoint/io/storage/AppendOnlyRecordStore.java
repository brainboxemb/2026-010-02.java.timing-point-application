package oss.relay.timing.timingpoint.io.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Generic append-only record storage boundary.
 *
 * <p>This I/O contract knows only opaque record payloads and physical recovery.
 * It deliberately has no TimingData, TimingNode or domain semantics.</p>
 */
public interface AppendOnlyRecordStore {

    LoadResult load() throws StoreException;

    void append(byte[] record) throws StoreException;

    final class Record {
        private final byte[] payload;

        public Record(byte[] payload) {
            if (payload == null || payload.length == 0) {
                throw new IllegalArgumentException("payload must not be empty");
            }
            this.payload = Arrays.copyOf(payload, payload.length);
        }

        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }

    final class LoadResult {
        private final List<Record> records;
        private final boolean repairedIncompleteTail;

        public LoadResult(List<Record> records, boolean repairedIncompleteTail) {
            if (records == null) {
                throw new IllegalArgumentException("records must not be null");
            }
            this.records = Collections.unmodifiableList(
                    new ArrayList<>(records));
            this.repairedIncompleteTail = repairedIncompleteTail;
        }

        public List<Record> records() {
            return records;
        }

        public boolean repairedIncompleteTail() {
            return repairedIncompleteTail;
        }
    }

    final class StoreException extends Exception {
        public StoreException(String message) {
            super(message);
        }

        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
