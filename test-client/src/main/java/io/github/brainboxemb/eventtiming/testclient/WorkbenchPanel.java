package io.github.brainboxemb.eventtiming.testclient;

import javafx.scene.Node;

import java.util.Objects;

/** Framework-neutral workbench panel description. */
record WorkbenchPanel(String id, String title, Node content) {
    WorkbenchPanel {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
    }
}
