package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecords;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Persistence contract whose production path uses scalar TimingRecord values.
 *
 * <p>The TimingData overload and LoadResult.records() remain as migration
 * compatibility for existing Java test/extensions. They are not used by the
 * production LogBook path.</p>
 */
public interface TimingDataPersistence {

    LoadResult load() throws PersistenceException;

    /** Production scalar append path. */
    default void append(TimingRecord record) throws PersistenceException {
        append(TimingRecords.asTimingData(record));
    }

    /** Legacy Java extension point retained during migration. */
    void append(TimingData data) throws PersistenceException;

    final class LoadResult {
        private final List<TimingRecord> records;
        private final boolean repairedIncompleteTail;

        /**
         * Accepts either TimingRecord or legacy TimingData values and stores only
         * TimingRecord internally.
         */
        public LoadResult(
                List<?> records,
                boolean repairedIncompleteTail) {
            failIfInvalidArgument(records != null, "records must not be null");
            List<TimingRecord> copy = new ArrayList<TimingRecord>(records.size());
            for (Object value : records) {
                failIfInvalidArgument(value != null, "records must not contain null");
                if (value instanceof TimingRecord) {
                    copy.add((TimingRecord) value);
                } else if (value instanceof TimingData) {
                    copy.add(TimingRecords.fromTimingData((TimingData) value));
                } else {
                    throw new IllegalArgumentException(
                            "records must contain TimingRecord or TimingData values");
                }
            }
            this.records = Collections.unmodifiableList(copy);
            this.repairedIncompleteTail = repairedIncompleteTail;
        }

        /** Production scalar recovery view. */
        public List<TimingRecord> timingRecords() {
            return records;
        }

        /** Legacy semantic recovery view materialized on request. */
        public List<TimingData> records() {
            List<TimingData> values = new ArrayList<TimingData>(records.size());
            for (TimingRecord record : records) {
                values.add(TimingRecords.asTimingData(record));
            }
            return Collections.unmodifiableList(values);
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
