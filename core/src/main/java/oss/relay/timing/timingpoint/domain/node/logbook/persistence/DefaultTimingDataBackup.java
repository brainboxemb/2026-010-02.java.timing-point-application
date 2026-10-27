package oss.relay.timing.timingpoint.domain.node.logbook.persistence;

import oss.relay.timing.timingpoint.io.storage.AppendOnlyRecordStore;
import oss.relay.timing.timingpoint.io.storage.RecordStoreBackup;

import java.nio.file.Path;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

public final class DefaultTimingDataBackup
        implements TimingDataBackup {
    private final RecordStoreBackup recordStoreBackup;

    public DefaultTimingDataBackup(
            RecordStoreBackup recordStoreBackup) {
        failIfInvalidArgument(
                recordStoreBackup != null,
                "recordStoreBackup must not be null");
        this.recordStoreBackup = recordStoreBackup;
    }

    @Override
    public Path backup() {
        try {
            return recordStoreBackup.backup();
        } catch (AppendOnlyRecordStore.StoreException ex) {
            throw new BackupException(
                    "Could not back up TimingData",
                    ex);
        }
    }

    @Override
    public Path requestBackupAndClearOnRestart() {
        try {
            return recordStoreBackup.requestBackupAndClearOnRestart();
        } catch (AppendOnlyRecordStore.StoreException ex) {
            throw new BackupException(
                    "Could not schedule TimingData backup+clear",
                    ex);
        }
    }
}
