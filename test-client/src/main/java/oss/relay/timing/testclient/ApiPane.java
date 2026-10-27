package oss.relay.timing.testclient;

import oss.relay.timing.testclient.central.CentralActions;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
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
    private final TextArea capabilities = new TextArea("NOT SYNCED");
    private final TextArea rawResponse = new TextArea();
    private final javafx.scene.Node rawDataPane;
    private final javafx.scene.Node apiInfoPane;
    private final TimingPane timingPane;
    // Only the latest target-bound request may update API presentation.
    private final ViewRequestEpoch apiRequests = new ViewRequestEpoch();

    ApiPane(
            Supplier<ApiClient> clientSupplier,
            ExecutorService requests,
            Consumer<String> feedback,
            Consumer<String> apiState,
            ClientLog clientLog,
            CentralActions centralActions,
            EngineeringClientActions clientActions) {
        if (clientSupplier == null || requests == null
                || feedback == null || apiState == null || clientLog == null
                || centralActions == null || clientActions == null) {
            throw new IllegalArgumentException("API pane dependencies must not be null");
        }
        this.clientSupplier = clientSupplier;
        this.requests = requests;
        this.feedback = feedback;
        this.apiState = apiState;
        this.clientLog = clientLog;

        // API metadata and explicit diagnostics belong in their own
        // engineering tab, not above the interpreted registrations table.
        GridPane identity = new GridPane();
        identity.setHgap(8);
        identity.setVgap(8);
        identity.addRow(0, new Label("Application"), application);
        identity.addRow(1, new Label("Version"), version);
        identity.addRow(2, new Label("API version"), apiVersion);
        versionButton.setText("Refresh version");
        statusButton.setText("Refresh status");
        capabilities.setEditable(false);
        capabilities.setWrapText(false);
        capabilities.setPrefRowCount(5);
        capabilities.setPromptText("No capabilities received");
        VBox apiInfo = new VBox(8, identity, new HBox(8, versionButton, statusButton),
                new Label("IF-03 capabilities — supported / enabled"), capabilities);
        apiInfo.getStyleClass().add("engineering-form");
        apiInfoPane = apiInfo;

        timingPane = new TimingPane(
                clientSupplier,
                requests,
                this::showRaw,
                feedback,
                apiState,
                clientLog,
                centralActions,
                clientActions);
        timingPane.setCapabilitiesListener(this::showCapabilities);

        rawResponse.setEditable(false);
        rawResponse.setWrapText(false);
        rawResponse.setPrefRowCount(7);
        VBox rawPane = new VBox(rawResponse);
        rawPane.getStyleClass().add("engineering-data-pane");
        VBox.setVgrow(rawResponse, Priority.ALWAYS);
        rawDataPane = rawPane;

        versionButton.setOnAction(event -> loadVersion());
        statusButton.setOnAction(event -> loadStatus());
    }

    javafx.scene.Node systemsPane() {
        return timingPane.systemsPane();
    }

    void setSystemHost(String host) {
        // A pending API request belongs to the previous host. Its completion
        // is ignored by the request epoch, so release its busy state here:
        // otherwise Version/Status stay disabled until a stale result arrives
        // (or forever if that request never completes).
        apiRequests.invalidate();
        setBusy(false);
        timingPane.setSystemHost(host);
        application.setText("-");
        version.setText("-");
        apiVersion.setText("-");
        rawResponse.clear();
        capabilities.setText("NOT SYNCED");
    }

    void setLogBookCountListener(java.util.function.Consumer<Long> listener) {
        timingPane.setLogBookCountListener(listener);
    }

    void setRegistrationScopeListener(Consumer<String> listener) {
        timingPane.setRegistrationScopeListener(listener);
    }

    void setSelectedNodeListener(Consumer<String> listener) {
        timingPane.setSelectedNodeListener(listener);
    }

    javafx.scene.Node timingNodePane() {
        return timingPane.timingNodePane();
    }

    javafx.scene.Node apiInfoPane() {
        return apiInfoPane;
    }

    boolean showAllRegistrations() {
        return timingPane.showAllRegistrations();
    }

    void setShowAllRegistrations(boolean all) {
        timingPane.setShowAllRegistrations(all);
    }

    boolean canLoadAllLogBook() {
        return timingPane.canLoadAllLogBook();
    }

    String loadAllLogBookLabel() {
        return timingPane.loadAllLogBookLabel();
    }

    void loadAllLogBook() {
        timingPane.loadAllLogBook();
    }

    void refreshRegistrationProjectionFromServer() {
        timingPane.refreshRegistrationProjectionFromServer();
    }

    javafx.scene.Node registrationPane() {
        return timingPane.registrationPane();
    }

    javafx.scene.Node simulationPane() {
        return timingPane.simulationPane();
    }

    javafx.scene.Node scriptsPane() {
        return timingPane.scriptsPane();
    }

    void close() {
        timingPane.close();
    }

    javafx.scene.Node registrationsPane() {
        return timingPane.registrationsPane();
    }

    javafx.scene.Node stageStartTimesPane() {
        return timingPane.stageStartTimesPane();
    }

    javafx.scene.Node logBookPane() {
        return timingPane.logBookPane();
    }

    javafx.scene.Node rawDataPane() {
        return rawDataPane;
    }

    void connected() {
        capabilities.setText("SYNCING");
        timingPane.connected();
    }

    void disconnected(boolean stale) {
        capabilities.setText(stale ? "STALE — reconnect and sync" : "NOT SYNCED");
        timingPane.disconnected(stale);
    }

    void applyStatusEvent(ApiEventClient.StatusEvent event) {
        timingPane.applyStatusEvent(event);
    }

    void applyTimingDataEvent(ApiEventClient.TimingDataEvent event) {
        timingPane.applyTimingDataEvent(event);
    }

    private void showCapabilities(ApiClient.CapabilitiesResult result) {
        capabilities.setText(CapabilityDisplay.format(result));
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
        final long requestGeneration = apiRequests.begin();
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
                    if (!apiRequests.isCurrent(requestGeneration)) return;
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
