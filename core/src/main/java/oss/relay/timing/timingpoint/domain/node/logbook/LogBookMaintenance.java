package oss.relay.timing.timingpoint.domain.node.logbook;

import java.nio.file.Path;

/** Engineering maintenance boundary for one node's persisted LogBook data. */
public interface LogBookMaintenance {

    Path backupTimingData();

    Path requestTimingDataClearOnRestart();
}
