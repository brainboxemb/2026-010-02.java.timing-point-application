package oss.relay.timing.timingpoint.domain.node.logbook.persistence;

import java.nio.file.Path;

/** Optional technical maintenance boundary for persisted TimingData. */
public interface TimingDataPersistenceMaintenance {

    Path backupTimingData()
            throws TimingDataPersistence.PersistenceException;

    Path requestTimingDataClearOnRestart()
            throws TimingDataPersistence.PersistenceException;
}
