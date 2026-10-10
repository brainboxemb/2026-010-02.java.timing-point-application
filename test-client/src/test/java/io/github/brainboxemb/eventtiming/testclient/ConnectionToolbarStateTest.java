package io.github.brainboxemb.eventtiming.testclient;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
