package oss.relay.timing.timingpoint.io.storage;

import java.nio.file.Path;

public interface RecordStoreBackup {

    Path backup()
            throws AppendOnlyRecordStore.StoreException;

    Path requestBackupAndClearOnRestart()
            throws AppendOnlyRecordStore.StoreException;
}
