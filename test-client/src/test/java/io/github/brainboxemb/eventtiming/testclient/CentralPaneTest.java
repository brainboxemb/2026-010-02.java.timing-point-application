package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralPaneTest {
    @Test
    void compactSystemsTextFitsExplorerSemantics() {
        CentralSystemSimulator.SystemView system =
                new CentralSystemSimulator.SystemView(
                        "A",
                        true,
                        Instant.parse("2026-10-24T09:00:00Z"),
                        1157L,
                        4L,
                        3L,
                        "OPEN",
                        Integer.valueOf(5),
                        null);

        String compact = CentralPane.compactSystemText(system);

        assertEquals("A · OPEN · Location 5", compact);
        assertFalse(compact.contains("Timing"));
        assertFalse(compact.contains("Race"));
        assertFalse(compact.contains("\n"));
    }

    @Test
    void protocolDetailsRemainAvailableInTooltipText() {
        CentralSystemSimulator.SystemView system =
                new CentralSystemSimulator.SystemView(
                        "A",
                        true,
                        Instant.parse("2026-10-24T09:00:00Z"),
                        1157L,
                        4L,
                        3L,
                        "OPEN",
                        Integer.valueOf(5),
                        null);

        String details = CentralPane.systemDetailText(system);

        assertTrue(details.contains("Heartbeat 2026-10-24T09:00:00Z"));
        assertTrue(details.contains("Timing head 1157"));
        assertTrue(details.contains("Race head 4"));
        assertTrue(details.contains("Race ACK 3"));
        assertTrue(details.contains("Location 5"));
    }
}
