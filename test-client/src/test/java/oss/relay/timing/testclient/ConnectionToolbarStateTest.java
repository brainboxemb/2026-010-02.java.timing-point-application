package oss.relay.timing.testclient;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConnectionToolbarStateTest {
    @Test
    void aggregateIgnoresStatelessHttpAndCountsPersistentLinks() {
        assertEquals(ConnectionToolbarState.NONE,
                ConnectionToolbarState.of(false, false, false));
        assertEquals(ConnectionToolbarState.PARTIAL,
                ConnectionToolbarState.of(true, false, false));
        assertEquals(ConnectionToolbarState.PARTIAL,
                ConnectionToolbarState.of(true, true, false));
        assertEquals(ConnectionToolbarState.ALL,
                ConnectionToolbarState.of(true, true, true));
    }

    @Test
    void reconnectIsOnlyAvailableForPartialConnectivity() {
        assertFalse(ConnectionToolbarState.NONE.reconnectAvailable());
        assertTrue(ConnectionToolbarState.PARTIAL.reconnectAvailable());
        assertFalse(ConnectionToolbarState.ALL.reconnectAvailable());
    }
}
