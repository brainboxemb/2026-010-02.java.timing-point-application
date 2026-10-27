package oss.relay.timing.timingpoint.io.storage;

import java.nio.file.Path;

/** Optional maintenance operations for an append-only record store. */
public interface AppendOnlyRecordStoreMaintenance {

    /** Creates one consistent physical backup and returns its path. */
    Path backup() throws AppendOnlyRecordStore.StoreException;

    /**
     * Durably schedules backup+clear for the next application startup/restart.
     *
     * @return the backup path reserved for that pending clear
     */
    Path requestClearOnRestart() throws AppendOnlyRecordStore.StoreException;
}
