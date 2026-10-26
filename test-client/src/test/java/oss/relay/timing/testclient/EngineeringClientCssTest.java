package oss.relay.timing.testclient;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the small set of SI-02 CSS semantics that are easy to regress silently. */
class EngineeringClientCssTest {

    @Test
    void dockHeadersLookLikeTabsAndSelectedActiveStatesRemainDistinct()
            throws Exception {
        String css;
        try (InputStream input = getClass()
                .getResourceAsStream("/engineering-client.css")) {
            assertNotNull(input, "Missing Engineering Client stylesheet");
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }

        assertTrue(
                css.contains(".header-region {\n"
                        + "    -fx-background-color: -bento-bg;"),
                "BentoFX tab strip must have its own surface");
        assertTrue(
                css.contains(".header {\n"
                        + "    -fx-background-color: -bento-bg-raised;")
                        && css.contains(
                                "-fx-border-color: transparent -bento-border "
                                        + "-bento-border transparent;"),
                "Every BentoFX header must have a visible tab fill and separator");
        assertTrue(
                css.contains("-si02-selected-bg: #dbeaf9;")
                        && css.contains(".header-pane .header:selected {\n"
                        + "    -fx-background-color: -si02-selected-bg;"),
                "Selected BentoFX tab must have a clear selected fill");
        assertTrue(
                css.contains(".header-pane:active .header:selected {\n"
                        + "    -fx-border-color: transparent -bento-border "
                        + "-bento-accent transparent;"),
                "Only the active leaf should add the accent underline");
    }

    @Test
    void centralMessageInspectorHasVisibleResizableDivider()
            throws Exception {
        String css;
        try (InputStream input = getClass()
                .getResourceAsStream("/engineering-client.css")) {
            assertNotNull(input, "Missing Engineering Client stylesheet");
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }

        assertTrue(
                css.contains(".engineering-data-split > .split-pane-divider {")
                        && css.contains("-fx-padding: 2px 0 2px 0;")
                        && css.contains(
                                ".engineering-data-split > .split-pane-divider:hover {"),
                "Central message table/detail divider must remain visible and draggable");
    }
}
