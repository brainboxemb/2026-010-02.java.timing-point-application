package oss.relay.eventtiming.timingpoint.domain.node.logbook.persistence;

import oss.relay.eventtiming.timingdata.TimingRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Persistence contract for scalar TimingRecord values. */
public interface TimingDataPersistence {

    LoadResult load() throws PersistenceException;

    void append(TimingRecord record) throws PersistenceException;

    /** Immutable result of one persistence recovery pass. */
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
