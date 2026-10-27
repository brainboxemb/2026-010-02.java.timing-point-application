package oss.relay.timing.timingpoint.domain.node.logbook;

import oss.relay.timing.timingpoint.domain.node.logbook.persistence.TimingDataBackup;

import java.nio.file.Path;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

public final class LogBookBackup {
    private final TimingDataBackup timingDataBackup;

    public LogBookBackup(
            TimingDataBackup timingDataBackup) {
        failIfInvalidArgument(
                timingDataBackup != null,
                "timingDataBackup must not be null");
        this.timingDataBackup = timingDataBackup;
    }

    public Path backupLogBook() {
        return timingDataBackup.backup();
    }

    public Path requestBackupAndClearOnRestart() {
        return timingDataBackup.requestBackupAndClearOnRestart();
    }
}
