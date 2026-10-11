package io.github.brainboxemb.eventtiming.testclient;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the small set of SI-02 CSS semantics that are easy to regress silently. */
class EngineeringClientCssTest {

    @Test
    void selectedDockTabHasNeutralFillAndActiveLeafAddsAccent() throws Exception {
        String css;
        try (InputStream input = getClass()
                .getResourceAsStream("/engineering-client.css")) {
            assertNotNull(input, "Missing Engineering Client stylesheet");
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        }

        assertTrue(
                css.contains("-si02-selected-bg: #dbeaf9;")
                        && css.contains(".header-pane .header:selected {\n"
                        + "    -fx-background-color: -si02-selected-bg;"),
                "Selected BentoFX tab must remain visibly distinct from the raised tab strip");
        assertTrue(
                css.contains(".header-pane:active .header:selected {\n"
                        + "    -fx-border-color: transparent transparent -bento-accent transparent;"),
                "Only the active leaf should add the accent underline");
    }
}
