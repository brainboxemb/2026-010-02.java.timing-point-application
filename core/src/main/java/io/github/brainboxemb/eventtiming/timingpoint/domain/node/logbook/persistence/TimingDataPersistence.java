package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * TimingData-specific persistence contract used by TimingNode.
 *
 * <p>The domain contract exposes TimingData semantics only. Its default
 * implementation maps those semantics onto the generic lower-layer record
 * storage boundary.</p>
 */
public interface TimingDataPersistence {

    /** Loads and validates the complete persisted TimingData stream. */
    LoadResult load() throws PersistenceException;

    /** Appends one committed TimingData record durably. */
    void append(TimingData data) throws PersistenceException;

    /** Immutable result of loading persisted TimingData history. */
    final class LoadResult {
        private final List<TimingData> records;
        private final boolean repairedIncompleteTail;

        /** Creates one immutable load result from validated records and tail-repair state. */
        public LoadResult(
                List<TimingData> records,
                boolean repairedIncompleteTail) {
            checkArgument(records != null, "records must not be null");
            List<TimingData> copy = new ArrayList<>(records.size());
            for (TimingData record : records) {
                checkArgument(record != null, "records must not contain null");
                copy.add(record);
            }
            this.records = Collections.unmodifiableList(copy);
            this.repairedIncompleteTail = repairedIncompleteTail;
        }

        /** Returns the immutable validated TimingData records in source order. */
        public List<TimingData> records() {
            return records;
        }

        /** Reports whether the lower append-only store repaired an incomplete final record. */
        public boolean repairedIncompleteTail() {
            return repairedIncompleteTail;
        }
    }

    /** Checked failure while loading, validating or appending TimingData persistence. */
    final class PersistenceException extends Exception {
        /** Creates a persistence failure without a lower-level cause. */
        public PersistenceException(String message) {
            super(message);
        }

        /** Creates a persistence failure with its lower-level cause. */
        public PersistenceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
