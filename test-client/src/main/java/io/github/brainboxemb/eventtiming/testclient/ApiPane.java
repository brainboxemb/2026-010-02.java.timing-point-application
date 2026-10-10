package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coordinates API identity and timing workbench state without owning layout. */
final class ApiPane {
    private final Supplier<ApiClient> clientSupplier;
    private final ExecutorService requests;
    private final Consumer<String> feedback;
    private final Consumer<String> apiState;
    private final ClientLog clientLog;

    private final Button versionButton = new Button("Version");
    private final Button statusButton = new Button("Status");
    private final Label application = valueLabel();
    private final Label version = valueLabel();
    private final Label apiVersion = valueLabel();
    private final TextArea rawResponse = new TextArea();
    private final javafx.scene.Node rawDataPane;
    private final TimingPane timingPane;

    ApiPane(
            Supplier<ApiClient> clientSupplier,
            ExecutorService requests,
            String initialPrefix,
            Consumer<String> feedback,
            Consumer<String> apiState,
            ClientLog clientLog) {
        if (clientSupplier == null || requests == null
                || feedback == null || apiState == null || clientLog == null) {
            throw new IllegalArgumentException("API pane dependencies must not be null");
        }
        this.clientSupplier = clientSupplier;
        this.requests = requests;
        this.feedback = feedback;
        this.apiState = apiState;
        this.clientLog = clientLog;

        HBox identityBar =
                new HBox(
                        10,
                        new Label("Application"),
                        application,
                        new Label("Version"),
                        version,
                        new Label("API version"),
                        apiVersion,
                        versionButton,
                        statusButton);

        timingPane = new TimingPane(
                clientSupplier,
                requests,
                initialPrefix,
                identityBar,
                this::showRaw,
                feedback,
                apiState,
                clientLog);

        rawResponse.setEditable(false);
        rawResponse.setWrapText(false);
        rawResponse.setPrefRowCount(7);
        VBox rawPane = new VBox(rawResponse);
        rawPane.setPadding(new Insets(8));
        VBox.setVgrow(rawResponse, Priority.ALWAYS);
        rawDataPane = rawPane;

        versionButton.setOnAction(event -> loadVersion());
        statusButton.setOnAction(event -> loadStatus());
    }

    javafx.scene.Node systemsPane() {
        return timingPane.systemsPane();
    }

    void setSystemHost(String host) {
        timingPane.setSystemHost(host);
    }

    void setLogBookCountListener(java.util.function.Consumer<Long> listener) {
        timingPane.setLogBookCountListener(listener);
    }

    javafx.scene.Node timingNodePane() {
        return timingPane.timingNodePane();
    }

    javafx.scene.Node registrationPane() {
        return timingPane.registrationPane();
    }

    javafx.scene.Node simulationPane() {
        return timingPane.simulationPane();
    }

    javafx.scene.Node registrationsPane() {
        return timingPane.registrationsPane();
    }

    javafx.scene.Node logBookPane() {
        return timingPane.logBookPane();
    }

    javafx.scene.Node rawDataPane() {
        return rawDataPane;
    }

    void connected() {
        timingPane.connected();
    }

    void disconnected(boolean stale) {
        timingPane.disconnected(stale);
    }

    void applyStatusEvent(ApiEventClient.StatusEvent event) {
        timingPane.applyStatusEvent(event);
    }

    void applyTimingDataEvent(ApiEventClient.TimingDataEvent event) {
        timingPane.applyTimingDataEvent(event);
    }

    private void loadVersion() {
        runRequest(
                "version",
                () -> clientSupplier.get().getVersion(),
                result -> {
                    application.setText(result.build().application());
                    version.setText(result.build().version());
                    apiVersion.setText(result.build().apiVersion());
                    showRaw(result.rawJson());
                });
    }

    private void loadStatus() {
        runRequest(
                "status",
                () -> clientSupplier.get().getStatus(),
                result -> {
                    timingPane.applyStatus(result);
                    showRaw(result.rawJson());
                });
    }

    private <T> void runRequest(
            String operation,
            CheckedSupplier<T> request,
            Consumer<T> success) {
        setBusy(true);
        feedback.accept("Requesting " + operation + "...");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return request.get();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    setBusy(false);
                    if (error != null) {
                        Throwable root = rootCause(error);
                        if (root instanceof ApiClient.ApiException apiError) {
                            apiState.accept("READY");
                            showRaw(apiError.rawJson());
                        } else {
                            apiState.accept("UNREACHABLE");
                            showRaw(root.toString());
                        }
                        clientLog.error("API " + operation + " failed: " + rootMessage(error));
                        feedback.accept("Error: " + rootMessage(error));
                        return;
                    }
                    apiState.accept("READY");
                    clientLog.info("API " + operation + " completed");
                    success.accept(result);
                    feedback.accept("OK");
                }));
    }

    private void showRaw(String value) {
        rawResponse.setText(value == null ? "" : value);
        rawResponse.positionCaret(0);
    }

    private void setBusy(boolean busy) {
        versionButton.setDisable(busy);
        statusButton.setDisable(busy);
    }

    private static Label valueLabel() {
        Label label = new Label("-");
        label.setWrapText(true);
        return label;
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = rootCause(error);
        String value = root.getMessage();
        return value == null || value.isBlank() ? root.toString() : value;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
