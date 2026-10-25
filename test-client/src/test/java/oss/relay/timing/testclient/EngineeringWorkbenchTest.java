package oss.relay.timing.testclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EngineeringWorkbenchTest {
    @Test
    void perspectiveTitlesFollowSelectedNode() {
        assertEquals("Node", EngineeringWorkbench.perspectiveTitle("Node", null));
        assertEquals("Central", EngineeringWorkbench.perspectiveTitle("Central", " "));
        assertEquals("Node A", EngineeringWorkbench.perspectiveTitle("Node", "A"));
        assertEquals(
                "Central TN-02",
                EngineeringWorkbench.perspectiveTitle("Central", " TN-02 "));
    }

    @Test
    void detachedWindowSizeKeepsSmallSourcesAndCapsLargeOnes() {
        assertEquals(480.0, EngineeringWorkbench.detachedWidth(320.0));
        assertEquals(640.0, EngineeringWorkbench.detachedWidth(640.0));
        assertEquals(960.0, EngineeringWorkbench.detachedWidth(1400.0));

        assertEquals(320.0, EngineeringWorkbench.detachedHeight(180.0));
        assertEquals(420.0, EngineeringWorkbench.detachedHeight(420.0));
        assertEquals(640.0, EngineeringWorkbench.detachedHeight(900.0));
    }

    @Test
    void detachedStageExtentAlsoRespectsMonitorBounds() {
        assertEquals(
                640.0,
                EngineeringWorkbench.detachedExtent(
                        900.0,
                        320.0,
                        640.0,
                        1080.0));
        assertEquals(
                500.0,
                EngineeringWorkbench.detachedExtent(
                        900.0,
                        320.0,
                        640.0,
                        500.0));
        assertEquals(
                280.0,
                EngineeringWorkbench.detachedExtent(
                        900.0,
                        320.0,
                        640.0,
                        280.0));
    }

    @Test
    void detachedPositionKeepsWholeWindowOnScreen() {
        assertEquals(
                0.0,
                EngineeringWorkbench.detachedPosition(
                        -50.0,
                        640.0,
                        0.0,
                        1920.0));
        assertEquals(
                1280.0,
                EngineeringWorkbench.detachedPosition(
                        1700.0,
                        640.0,
                        0.0,
                        1920.0));
        assertEquals(
                -1920.0,
                EngineeringWorkbench.detachedPosition(
                        -2500.0,
                        960.0,
                        -1920.0,
                        0.0));
        assertEquals(
                0.0,
                EngineeringWorkbench.detachedPosition(
                        Double.NaN,
                        640.0,
                        0.0,
                        1080.0));
    }

    @Test
    void perspectiveNameMustBePresent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineeringWorkbench.perspectiveTitle(" ", "A"));
    }
}
