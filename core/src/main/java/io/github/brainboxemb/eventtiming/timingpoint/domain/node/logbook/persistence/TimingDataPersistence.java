package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Persistence contract for the shared scalar timing record.
 *
 * <p>The historical class name remains temporarily stable for source
 * compatibility. The contract itself no longer transports TimingData objects.</p>
 */
public interface TimingDataPersistence {

    /** Loads and validates the complete persisted timing-record stream. */
    LoadResult load() throws PersistenceException;

    /** Appends one committed scalar record durably. */
    void append(TimingRecord record) throws PersistenceException;

    /** Immutable result of loading persisted timing history. */
    final class LoadResult {
        private final List<TimingRecord> records;
        private final boolean repairedIncompleteTail;

        public LoadResult(
                List<TimingRecord> records,
                boolean repairedIncompleteTail) {
            failIfInvalidArgument(records != null, "records must not be null");
            List<TimingRecord> copy = new ArrayList<TimingRecord>(records.size());
            for (TimingRecord record : records) {
                failIfInvalidArgument(record != null, "records must not contain null");
                copy.add(record);
            }
            this.records = Collections.unmodifiableList(copy);
            this.repairedIncompleteTail = repairedIncompleteTail;
        }

        public List<TimingRecord> records() {
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
