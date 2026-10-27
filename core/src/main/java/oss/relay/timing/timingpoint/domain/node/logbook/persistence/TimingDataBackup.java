package oss.relay.timing.timingpoint.domain.node.logbook.persistence;

import java.nio.file.Path;

public interface TimingDataBackup {

    Path backup();

    Path requestBackupAndClearOnRestart();

    final class BackupException extends RuntimeException {
        public BackupException(
                String message,
                Throwable cause) {
            super(message, cause);
        }
    }
}
