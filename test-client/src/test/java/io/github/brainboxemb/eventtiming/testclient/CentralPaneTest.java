package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralPaneTest {
    @Test
    void compactNodeTextFitsExplorerSemantics() {
        CentralSystemSimulator.NodeView node =
                new CentralSystemSimulator.NodeView(
                        "A",
                        true,
                        Instant.parse("2026-10-24T09:00:00Z"),
                        1157L,
                        4L,
                        3L,
                        "OPEN",
                        Integer.valueOf(5),
                        null);

        String compact = CentralPane.compactNodeText(node);

        assertEquals("A · OPEN · Location 5", compact);
        assertFalse(compact.contains("Timing"));
        assertFalse(compact.contains("Race"));
        assertFalse(compact.contains("\n"));
    }

    @Test
    void protocolDetailsRemainAvailableInNodeTooltipText() {
        CentralSystemSimulator.NodeView node =
                new CentralSystemSimulator.NodeView(
                        "A",
                        true,
                        Instant.parse("2026-10-24T09:00:00Z"),
                        1157L,
                        4L,
                        3L,
                        "OPEN",
                        Integer.valueOf(5),
                        null);

        String details = CentralPane.nodeDetailText(node);

        assertTrue(details.contains("Heartbeat 2026-10-24T09:00:00Z"));
        assertTrue(details.contains("Timing head 1157"));
        assertTrue(details.contains("Race head 4"));
        assertTrue(details.contains("Race ACK 3"));
        assertTrue(details.contains("Location 5"));
    }

    @Test
    void compactLocationTextOmitsSequenceAndTimestamp() {
        CentralSystemSimulator.LocationView location =
                new CentralSystemSimulator.LocationView(
                        5,
                        "OPEN",
                        "A",
                        1157L,
                        Instant.parse("2026-10-24T09:00:00Z"));

        String compact = CentralPane.compactLocationText(location);

        assertEquals("5 · OPEN · A", compact);
        assertFalse(compact.contains("1157"));
        assertFalse(compact.contains("2026"));
    }

    @Test
    void unknownLocationDoesNotInventNode() {
        CentralSystemSimulator.LocationView location =
                new CentralSystemSimulator.LocationView(
                        6,
                        "UNKNOWN",
                        null,
                        0L,
                        null);

        assertEquals("6 · UNKNOWN", CentralPane.compactLocationText(location));
    }
}
