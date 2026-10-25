package oss.relay.eventtiming.testclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewRequestEpochTest {
    @Test
    void obsoleteSyncAndSelectionCompletionsCannotOverwriteNewerView() {
        ViewRequestEpoch epoch = new ViewRequestEpoch();
        long firstSync = epoch.begin();
        assertTrue(epoch.isCurrent(firstSync));
        long newerSync = epoch.begin();
        assertFalse(epoch.isCurrent(firstSync));
        assertTrue(epoch.isCurrent(newerSync));

        epoch.invalidate(); // Disconnect or change target.
        assertFalse(epoch.isCurrent(newerSync));
        long selectedNodeLoad = epoch.begin();
        assertTrue(epoch.isCurrent(selectedNodeLoad));
        epoch.invalidate(); // Reconnect / select a different node.
        assertFalse(epoch.isCurrent(selectedNodeLoad));
    }
}
