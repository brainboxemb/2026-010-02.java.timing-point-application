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
    void perspectiveNameMustBePresent() {
        assertThrows(
                IllegalArgumentException.class,
                () -> EngineeringWorkbench.perspectiveTitle(" ", "A"));
    }
}
