package io.github.brainboxemb.eventtiming.testclient;

import javafx.beans.value.ObservableValue;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;

/**
 * Compact read-only log mirror used below the Timing/API workbench.
 *
 * <p>The full Device Log and Client Log tabs remain available. This pane only
 * mirrors their text so timing work and recent diagnostics can stay visible at
 * the same time.</p>
 */
final class WorkbenchLogPane extends TabPane {

    WorkbenchLogPane(
            ObservableValue<String> deviceLogText,
            ObservableValue<String> clientLogText) {
        if (deviceLogText == null || clientLogText == null) {
            throw new IllegalArgumentException(
                    "log text sources must not be null");
        }

        setTabClosingPolicy(
                TabClosingPolicy.UNAVAILABLE);
        setPrefHeight(
                180);
        setMinHeight(
                120);

        getTabs().setAll(
                tab(
                        "Device Log",
                        mirroredLog(
                                deviceLogText)),
                tab(
                        "Client Log",
                        mirroredLog(
                                clientLogText)));
    }

    private static Tab tab(
            String title,
            TextArea log) {
        Tab tab =
                new Tab(
                        title,
                        log);
        tab.setClosable(
                false);
        return tab;
    }

    private static TextArea mirroredLog(
            ObservableValue<String> source) {
        TextArea log =
                new TextArea();
        log.setEditable(
                false);
        log.setWrapText(
                false);
        log.setStyle(
                "-fx-control-inner-background: black;"
                        + "-fx-text-fill: #e8e8e8;"
                        + "-fx-font-family: 'Consolas';"
                        + "-fx-font-size: 11px;");
        log.textProperty().bind(
                source);
        return log;
    }
}
