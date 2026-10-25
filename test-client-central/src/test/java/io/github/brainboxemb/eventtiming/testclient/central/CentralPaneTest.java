package io.github.brainboxemb.eventtiming.testclient.central;

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

        String compact = CentralPane.compactNodeText(
                node,
                Instant.parse("2026-10-24T09:00:30Z"));

        assertEquals("A [05] · H - 30s · CNT 1157", compact);
        assertFalse(compact.contains("Timing"));
        assertFalse(compact.contains("Race"));
        assertFalse(compact.contains("\n"));
    }

    @Test
    void locationMarkerKeepsAtLeastTwoDigits() {
        CentralSystemSimulator.NodeView node =
                new CentralSystemSimulator.NodeView(
                        "A",
                        true,
                        null,
                        12L,
                        0L,
                        0L,
                        "OPEN",
                        Integer.valueOf(1),
                        null);

        assertEquals(
                "A [01] · H --- · CNT  12",
                CentralPane.compactNodeText(
                        node,
                        Instant.parse("2026-10-24T09:00:30Z")));
    }

    @Test
    void heartbeatAgeIsUnavailableBeforeFirstHeartbeat() {
        CentralSystemSimulator.NodeView node =
                new CentralSystemSimulator.NodeView(
                        "A",
                        true,
                        null,
                        0L,
                        0L,
                        0L,
                        "UNKNOWN",
                        null,
                        null);

        assertEquals(
                "A [??] · H --- · CNT   0",
                CentralPane.compactNodeText(
                        node,
                        Instant.parse("2026-10-24T09:00:30Z")));
    }

    @Test
    void closedNodeUsesDashLocationMarker() {
        CentralSystemSimulator.NodeView node =
                new CentralSystemSimulator.NodeView(
                        "A",
                        true,
                        Instant.parse("2026-10-24T09:00:00Z"),
                        999L,
                        0L,
                        0L,
                        "CLOSED",
                        null,
                        null);

        assertEquals(
                "A [--] · H - 30s · CNT 999",
                CentralPane.compactNodeText(
                        node,
                        Instant.parse("2026-10-24T09:00:30Z")));
    }

    @Test
    void heartbeatAndCountUseLeadingSpacesForAlignment() {
        Instant now = Instant.parse("2026-10-24T09:00:09Z");

        assertEquals(
                "H -  9s",
                CentralPane.heartbeatAgeText(
                        Instant.parse("2026-10-24T09:00:00Z"),
                        now));
        assertEquals("  9", CentralPane.paddedCount(9L));
        assertEquals(" 99", CentralPane.paddedCount(99L));
        assertEquals("999", CentralPane.paddedCount(999L));
        assertEquals("1000", CentralPane.paddedCount(1000L));
    }

    @Test
    void heartbeatAgeIsClampedToNarrowExplorerRange() {
        Instant now = Instant.parse("2026-10-24T09:20:00Z");

        assertEquals(
                "H -999s",
                CentralPane.heartbeatAgeText(
                        Instant.parse("2026-10-24T08:00:00Z"),
                        now));
        assertEquals(
                "H -  0s",
                CentralPane.heartbeatAgeText(
                        Instant.parse("2026-10-24T09:20:05Z"),
                        now));
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
        assertTrue(details.contains("DN ack 4"));
        assertTrue(details.contains("DN pending 3"));
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
