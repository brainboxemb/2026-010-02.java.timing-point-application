package io.github.brainboxemb.eventtiming.testclient;

import javafx.beans.value.ObservableValue;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.control.TextArea;

/**
 * Compact read-only log mirror used below the Timing/API workbench.
 *
 * <p>The full Device Log and Client Log tabs remain available. This pane only
 * mirrors their text so timing work and recent diagnostics can stay visible at
 * the same time.</p>
 */
final class WorkbenchLogPane extends VBox {

    WorkbenchLogPane(
            ObservableValue<String> deviceLogText,
            ObservableValue<String> clientLogText) {
        if (deviceLogText == null || clientLogText == null) {
            throw new IllegalArgumentException(
                    "log text sources must not be null");
        }

        setSpacing(10);

        TitledPane device =
                titledLog(
                        "Device Log",
                        mirroredLog(deviceLogText));
        TitledPane client =
                titledLog(
                        "Client Log",
                        mirroredLog(clientLogText));

        getChildren().setAll(device, client);
        VBox.setVgrow(device, Priority.ALWAYS);
        VBox.setVgrow(client, Priority.ALWAYS);
    }

    private static TitledPane titledLog(
            String title,
            TextArea log) {
        TitledPane pane =
                new TitledPane(title, log);
        pane.setCollapsible(false);
        pane.setMinHeight(120);
        return pane;
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
        log.textProperty().addListener(
                (ignored, previous, current) ->
                        log.positionCaret(
                                log.getLength()));
        return log;
    }
}
