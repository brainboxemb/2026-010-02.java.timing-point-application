package io.github.brainboxemb.eventtiming.testclient;

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
    void perspectiveNameMustBePresent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineeringWorkbench.perspectiveTitle(" ", "A"));
    }
}
