package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.persistence;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TimingData-specific persistence contract used by TimingNode.
 *
 * <p>The domain contract exposes TimingData semantics only. Its default
 * implementation maps those semantics onto the generic lower-layer record
 * storage boundary.</p>
 */
public interface TimingDataPersistence {

    LoadResult load() throws PersistenceException;

    void append(TimingData data) throws PersistenceException;

    final class LoadResult {
        private final List<TimingData> records;
        private final boolean repairedIncompleteTail;

        public LoadResult(
                List<TimingData> records,
                boolean repairedIncompleteTail) {
            if (records == null) {
                throw new IllegalArgumentException("records must not be null");
            }
            List<TimingData> copy = new ArrayList<>(records.size());
            for (TimingData record : records) {
                if (record == null) {
                    throw new IllegalArgumentException(
                            "records must not contain null");
                }
                copy.add(record);
            }
            this.records = Collections.unmodifiableList(copy);
            this.repairedIncompleteTail = repairedIncompleteTail;
        }

        public List<TimingData> records() {
            return records;
        }

        public boolean repairedIncompleteTail() {
            return repairedIncompleteTail;
        }
    }

    final class PersistenceException extends Exception {
        public PersistenceException(String message) {
            super(message);
        }

        public PersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
