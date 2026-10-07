package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TestClientFxApplication extends Application {
    private final TestClientBuildIdentity clientBuild = TestClientBuildIdentity.embedded();
    private final ExecutorService requests = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dc-request");
        thread.setDaemon(true);
        return thread;
    });

    private final Label feedback = new Label("Ready");

    private final Button apiBoundary = new Button();
    private final Button eventBoundary = new Button();
    private final Button terminalBoundary = new Button();
    private final Button deviceLogBoundary = new Button();
    private final Button clientLogBoundary = new Button();
    private final TextField targetHost = new TextField();
    private final Button applyTarget = new Button("Apply target");

    private final ApiEventClient eventClient = new ApiEventClient();
    private final Label eventType = valueLabel();
    private final Label eventOccurredAt = valueLabel();
    private final Label eventTimingNodeId = valueLabel();
    private final Label eventTimingNodeLifecycle = valueLabel();
    private final TextArea eventLog = new TextArea();

    private final RemoteShellClient shellClient = new RemoteShellClient();
    private final TextArea terminal = new TextArea();
    private final TextField terminalInput = new TextField();
    private final Button terminalSend = new Button("Send");

    private final LiveLogClient liveLogClient = new LiveLogClient();
    private final ComboBox<String> deviceLogLevel = new ComboBox<>();
    private final Button applyDeviceLogLevel = new Button("Apply level");
    private final Label currentDeviceLogLevel = valueLabel();
    private final ComboBox<String> clientLogLevel = new ComboBox<>();
    private final Button applyClientLogLevel = new Button("Apply level");
    private final Label currentClientLogLevel = valueLabel();
    private final TextArea liveLogs = new TextArea();
    private final TextArea clientLogs = new TextArea();

    private ClientConfig config;
    private Path configPath;
    private String activeTargetHost;
    private ClientLog clientLog;
    private ApiPane apiPane;

    @Override
    public void start(Stage stage) throws Exception {
        configPath = resolveConfigPath();
        config = ClientConfig.load(configPath);
        activeTargetHost = config.host();
        clientLog = ClientLog.open(config.clientLogPath(), config.clientLogLevel());
        clientLog.info("Development Client starting with config " + configPath);

        configureBoundaryButtons();

        apiPane = new ApiPane(
                this::client,
                requests,
                config.registrationPrefix(),
                liveLogs.textProperty(),
                clientLogs.textProperty(),
                feedback::setText,
                this::setApiState,
                clientLog);

        Tab apiTab = tab("API", apiPane);
        Tab eventsTab = tab("Events", eventsPane());
        Tab deviceLogTab = tab("Device Log", deviceLogPane());
        Tab terminalTab = tab("Terminal", terminalPane());
        Tab clientLogTab = tab("Client Log", clientLogPane());
        TabPane tabs = new TabPane(
                apiTab,
                eventsTab,
                deviceLogTab,
                terminalTab,
                clientLogTab);

        MenuItem about = new MenuItem("About");
        about.setOnAction(event -> showAbout(stage));
        Menu help = new Menu("Help");
        help.getItems().add(about);
        MenuBar menuBar = new MenuBar(help);

        VBox top = new VBox(menuBar, targetBar());

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(tabs);
        root.setBottom(feedback);
        BorderPane.setMargin(feedback, new Insets(0, 12, 12, 12));

        stage.setTitle(clientBuild.application() + " — " + clientBuild.version());
        stage.setScene(new Scene(root, 1240, 820));
        stage.show();
        clientLog.info("Development Client UI ready");
    }

    private Path resolveConfigPath() {
        String specified = getParameters().getNamed().get("config");
        return specified == null || specified.isBlank()
                ? ClientConfig.defaultPath()
                : Path.of(specified);
    }

    private HBox targetBar() {
        targetHost.setText(activeTargetHost);
        targetHost.setPromptText("host or IP");
        targetHost.setPrefColumnCount(15);
        targetHost.setTooltip(new Tooltip(
                "Startup default from " + configPath
                        + ". Edit the host/IP and apply it for all external boundaries."));
        targetHost.setOnAction(event -> applyTargetHost());
        applyTarget.setOnAction(event -> applyTargetHost());

        apiBoundary.setTooltip(new Tooltip(
                "IF-03 HTTP is stateless. Click to check that the API at the active target is reachable."));
        clientLogBoundary.setDisable(true);

        HBox bar = new HBox(
                8,
                new Label("Target"),
                targetHost,
                applyTarget,
                apiBoundary,
                eventBoundary,
                terminalBoundary,
                deviceLogBoundary,
                clientLogBoundary);
        HBox.setHgrow(targetHost, Priority.NEVER);
        bar.setPadding(new Insets(10, 12, 10, 12));
        return bar;
    }

    private void configureBoundaryButtons() {
        setApiState("CHECK");
        setEventConnected(false, "CONNECT");
        setShellConnected(false, "CONNECT");
        setLogConnected(false, "CONNECT");
        clientLogBoundary.setText("Client log\nACTIVE");

        apiBoundary.setOnAction(event -> checkApi());
        eventBoundary.setOnAction(event -> {
            if (eventClient.isConnected()) {
                disconnectEvents();
            } else {
                connectEvents();
            }
        });
        terminalBoundary.setOnAction(event -> {
            if (shellClient.isConnected()) {
                shellClient.disconnect();
            } else {
                connectShell();
            }
        });
        deviceLogBoundary.setOnAction(event -> {
            if (liveLogClient.isConnected()) {
                liveLogClient.disconnect();
            } else {
                connectLogs();
            }
        });
    }

    private VBox eventsPane() {
        GridPane values = grid();
        addRow(values, 0, "Event type", eventType);
        addRow(values, 1, "Occurred at", eventOccurredAt);
        addRow(values, 2, "Timing node", eventTimingNodeId);
        addRow(values, 3, "State", eventTimingNodeLifecycle);

        eventLog.setEditable(false);
        eventLog.setWrapText(false);
        eventLog.setPrefRowCount(18);
        TitledPane logPane = new TitledPane("Received events (raw JSON)", eventLog);
        logPane.setCollapsible(false);

        VBox pane = new VBox(10, values, logPane);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(logPane, Priority.ALWAYS);
        return pane;
    }

    private VBox terminalPane() {
        terminal.setEditable(false);
        terminal.setWrapText(false);
        terminal.setStyle(
                "-fx-control-inner-background: black;"
                        + "-fx-text-fill: #e8e8e8;"
                        + "-fx-font-family: 'Consolas';"
                        + "-fx-font-size: 13px;");
        VBox.setVgrow(terminal, Priority.ALWAYS);

        terminalInput.setPromptText("command");
        terminalInput.setDisable(true);
        terminalSend.setDisable(true);
        HBox.setHgrow(terminalInput, Priority.ALWAYS);
        HBox input = new HBox(8, terminalInput, terminalSend);

        terminalSend.setOnAction(event -> sendShellCommand());
        terminalInput.setOnAction(event -> sendShellCommand());

        VBox pane = new VBox(8, terminal, input);
        pane.setPadding(new Insets(12));
        return pane;
    }

    private VBox deviceLogPane() {
        deviceLogLevel.setDisable(true);
        applyDeviceLogLevel.setDisable(true);
        deviceLogLevel.getItems().setAll("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        deviceLogLevel.setValue("INFO");

        HBox deviceLevel = new HBox(
                8,
                new Label("SI-01 current level"),
                currentDeviceLogLevel,
                new Label("Set level"),
                deviceLogLevel,
                applyDeviceLogLevel);

        liveLogs.setEditable(false);
        liveLogs.setWrapText(false);
        liveLogs.setStyle(
                "-fx-control-inner-background: black;"
                        + "-fx-text-fill: #e8e8e8;"
                        + "-fx-font-family: 'Consolas';"
                        + "-fx-font-size: 12px;");

        applyDeviceLogLevel.setOnAction(event -> applyDeviceLogLevel());

        VBox pane = new VBox(8, deviceLevel, liveLogs);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(liveLogs, Priority.ALWAYS);
        return pane;
    }

    private VBox clientLogPane() {
        clientLogLevel.getItems().setAll("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        clientLogLevel.setValue(clientLog.level());
        currentClientLogLevel.setText(clientLog.level());

        HBox clientLevel = new HBox(
                8,
                new Label("Client current level"),
                currentClientLogLevel,
                new Label("Set level"),
                clientLogLevel,
                applyClientLogLevel);

        clientLogs.setEditable(false);
        clientLogs.setWrapText(false);
        clientLogs.setText(clientLog.snapshot());
        clientLogs.setStyle(
                "-fx-control-inner-background: black;"
                        + "-fx-text-fill: #e8e8e8;"
                        + "-fx-font-family: 'Consolas';"
                        + "-fx-font-size: 12px;");
        clientLog.subscribe(line -> Platform.runLater(() -> {
            clientLogs.appendText(line);
            clientLogs.positionCaret(clientLogs.getLength());
        }));

        applyClientLogLevel.setOnAction(event -> applyClientLogLevel());

        VBox pane = new VBox(8, clientLevel, clientLogs);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(clientLogs, Priority.ALWAYS);
        return pane;
    }

    private void connectEvents() {
        if (!applyTargetHost()) {
            return;
        }
        eventBoundary.setDisable(true);
        eventBoundary.setText("Events :" + config.eventPort() + "\nCONNECTING");
        clientLog.info("Connecting IF-03 Events to " + eventEndpoint());

        try {
            eventClient.connect(eventEndpoint(), new ApiEventClient.Listener() {
                @Override
                public void onConnected() {
                    Platform.runLater(() -> {
                        setEventConnected(true, "CONNECTED");
                        apiPane.connected();
                        clientLog.info("IF-03 Events connected");
                    });
                }

                @Override
                public void onEvent(ApiEventClient.ApiEvent event) {
                    Platform.runLater(() -> showEvent(event));
                }

                @Override
                public void onClosed(int statusCode, String reason) {
                    Platform.runLater(() -> {
                        setEventConnected(false, "CONNECT");
                        apiPane.disconnected(true);
                        clientLog.warn("IF-03 Events disconnected (" + statusCode + ")");
                    });
                }

                @Override
                public void onError(String message) {
                    Platform.runLater(() -> {
                        clientLog.error("IF-03 Events error: " + message);
                        if (!eventClient.isConnected()) {
                            setEventConnected(false, "CONNECT");
                            apiPane.disconnected(true);
                        }
                        feedback.setText("Events error: " + message);
                    });
                }
            }).whenComplete((ignored, error) -> {
                if (error != null) {
                    Platform.runLater(() -> {
                        Throwable cause = rootCause(error);
                        setEventConnected(false, "CONNECT");
                        apiPane.disconnected(true);
                        clientLog.error("IF-03 Events connect failed: " + rootMessage(cause));
                        feedback.setText("Events error: " + rootMessage(cause));
                    });
                }
            });
        } catch (RuntimeException ex) {
            setEventConnected(false, "CONNECT");
            clientLog.error("IF-03 Events connect failed: " + ex.getMessage());
        }
    }

    private void disconnectEvents() {
        eventBoundary.setText("Events :" + config.eventPort() + "\nDISCONNECTING");
        eventClient.disconnect();
        if (!eventClient.isConnected()) {
            setEventConnected(false, "CONNECT");
            apiPane.disconnected(true);
            clientLog.info("IF-03 Events disconnected");
        }
    }

    private void showEvent(ApiEventClient.ApiEvent event) {
        eventType.setText(event.eventType());
        eventOccurredAt.setText(event.occurredAt().toString());

        if (event instanceof ApiEventClient.StatusEvent statusEvent) {
            if (statusEvent.status().nodes().isEmpty()) {
                eventTimingNodeId.setText("-");
                eventTimingNodeLifecycle.setText("-");
            } else {
                var node = statusEvent.status().nodes().get(0);
                eventTimingNodeId.setText(node.id());
                eventTimingNodeLifecycle.setText(node.state());
            }
            apiPane.applyStatusEvent(statusEvent);
        } else if (event instanceof ApiEventClient.TimingDataEvent dataEvent) {
            eventTimingNodeId.setText(dataEvent.timingData().timingNodeId());
            eventTimingNodeLifecycle.setText("-");
            apiPane.applyTimingDataEvent(dataEvent);
        } else {
            eventTimingNodeId.setText("-");
            eventTimingNodeLifecycle.setText("-");
        }

        if (!eventLog.getText().isEmpty()) {
            eventLog.appendText(System.lineSeparator());
        }
        eventLog.appendText(event.rawJson());
        eventLog.appendText(System.lineSeparator());
        eventLog.positionCaret(eventLog.getLength());
    }

    private void connectLogs() {
        if (!applyTargetHost()) {
            return;
        }
        deviceLogBoundary.setDisable(true);
        deviceLogBoundary.setText(
                "Device log :" + config.loggingServerPort() + "\nCONNECTING");
        clientLog.info("Connecting SI-01 device log");

        CompletableFuture
                .runAsync(() -> {
                    try {
                        liveLogClient.connect(
                                activeTargetHost,
                                config.loggingServerPort(),
                                new LiveLogClient.Listener() {
                                    @Override
                                    public void onConnected() {
                                        Platform.runLater(() -> {
                                            setLogConnected(true, "CONNECTED");
                                            clientLog.info("SI-01 device log connected");
                                        });
                                    }

                                    @Override
                                    public void onLog(LiveLogClient.LogEntry entry) {
                                        Platform.runLater(() -> appendDeviceLog(entry));
                                    }

                                    @Override
                                    public void onLevel(String level) {
                                        Platform.runLater(() -> {
                                            currentDeviceLogLevel.setText(level);
                                            deviceLogLevel.setValue(level);
                                        });
                                    }

                                    @Override
                                    public void onDisconnected() {
                                        Platform.runLater(() -> {
                                            setLogConnected(false, "CONNECT");
                                            clientLog.info("SI-01 device log disconnected");
                                        });
                                    }

                                    @Override
                                    public void onError(String message) {
                                        Platform.runLater(() -> {
                                            clientLog.error("SI-01 device log error: " + message);
                                            feedback.setText("Device log error: " + message);
                                        });
                                    }
                                });
                        liveLogClient.requestLevel();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        Throwable cause = rootCause(error);
                        setLogConnected(false, "CONNECT");
                        clientLog.error("SI-01 device log connect failed: "
                                + rootMessage(cause));
                        feedback.setText("Device log error: " + rootMessage(cause));
                    }
                }));
    }

    private void applyDeviceLogLevel() {
        try {
            liveLogClient.setLevel(deviceLogLevel.getValue());
            clientLog.info("Requested SI-01 log level " + deviceLogLevel.getValue());
        } catch (Exception ex) {
            clientLog.error("SI-01 log level request failed: " + ex.getMessage());
            feedback.setText("Device log error: " + ex.getMessage());
        }
    }

    private void applyClientLogLevel() {
        try {
            clientLog.setLevel(clientLogLevel.getValue());
            currentClientLogLevel.setText(clientLog.level());
            clientLogLevel.setValue(clientLog.level());
            feedback.setText("Client log level " + clientLog.level());
            clientLog.info("Development Client log level changed to " + clientLog.level());
        } catch (RuntimeException ex) {
            feedback.setText("Client log error: " + ex.getMessage());
        }
    }

    private void appendDeviceLog(LiveLogClient.LogEntry entry) {
        liveLogs.appendText(entry.formatted());
        liveLogs.positionCaret(liveLogs.getLength());
    }

    private void connectShell() {
        if (!applyTargetHost()) {
            return;
        }
        terminalBoundary.setDisable(true);
        terminalBoundary.setText(
                "Terminal :" + config.shellPort() + "\nCONNECTING");
        clientLog.info("Connecting Remote Shell");

        CompletableFuture
                .runAsync(() -> {
                    try {
                        shellClient.connect(
                                activeTargetHost,
                                config.shellPort(),
                                new RemoteShellClient.Listener() {
                                    @Override
                                    public void onText(String text) {
                                        Platform.runLater(() -> {
                                            terminal.appendText(text);
                                            terminal.positionCaret(terminal.getLength());
                                        });
                                    }

                                    @Override
                                    public void onDisconnected() {
                                        Platform.runLater(() -> {
                                            setShellConnected(false, "CONNECT");
                                            clientLog.info("Remote Shell disconnected");
                                        });
                                    }

                                    @Override
                                    public void onError(String message) {
                                        Platform.runLater(() -> {
                                            clientLog.error("Remote Shell error: " + message);
                                            feedback.setText("Terminal error: " + message);
                                        });
                                    }
                                });
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        Throwable cause = rootCause(error);
                        setShellConnected(false, "CONNECT");
                        clientLog.error("Remote Shell connect failed: " + rootMessage(cause));
                        feedback.setText("Terminal error: " + rootMessage(cause));
                    } else {
                        setShellConnected(true, "CONNECTED");
                        terminalInput.requestFocus();
                        clientLog.info("Remote Shell connected");
                    }
                }));
    }

    private void sendShellCommand() {
        String command = terminalInput.getText();
        if (command == null || command.trim().isEmpty()) {
            return;
        }
        try {
            terminal.appendText(command + System.lineSeparator());
            terminal.positionCaret(terminal.getLength());
            shellClient.send(command);
            clientLog.debug("Remote Shell command sent: " + command);
            terminalInput.clear();
        } catch (Exception ex) {
            clientLog.error("Remote Shell send failed: " + ex.getMessage());
            feedback.setText("Terminal error: " + ex.getMessage());
        }
    }

    private ApiClient client() {
        return new ApiClient(apiEndpoint());
    }

    private URI apiEndpoint() {
        return URI.create("http://" + uriHost(activeTargetHost) + ":" + config.apiHttpPort());
    }

    private URI eventEndpoint() {
        return URI.create(
                "ws://" + uriHost(activeTargetHost) + ":" + config.eventPort() + "/api/v1/events");
    }

    private static String uriHost(String host) {
        return host.indexOf(':') >= 0 && !host.startsWith("[")
                ? "[" + host + "]"
                : host;
    }

    private boolean applyTargetHost() {
        String value = targetHost.getText() == null ? "" : targetHost.getText().trim();
        if (value.isEmpty()) {
            feedback.setText("Target host/IP must not be empty");
            return false;
        }
        if (value.contains("://") || value.contains("/") || value.contains("\\")) {
            feedback.setText("Target must be a host or IP address, not a URL");
            return false;
        }
        if (value.equals(activeTargetHost)) {
            targetHost.setText(activeTargetHost);
            return true;
        }

        if (eventClient.isConnected()) {
            disconnectEvents();
        }
        if (shellClient.isConnected()) {
            shellClient.disconnect();
        }
        if (liveLogClient.isConnected()) {
            liveLogClient.disconnect();
        }

        activeTargetHost = value;
        targetHost.setText(activeTargetHost);
        setApiState("CHECK");
        apiPane.disconnected(true);
        feedback.setText("Target changed to " + activeTargetHost);
        clientLog.info("Development Client target changed to " + activeTargetHost);
        return true;
    }

    private void checkApi() {
        if (!applyTargetHost()) {
            return;
        }
        setApiState("CHECKING");
        feedback.setText("Checking API " + apiEndpoint() + "...");
        clientLog.info("Checking IF-03 API at " + apiEndpoint());

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return client().getVersion();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        setApiState("UNREACHABLE");
                        clientLog.error("IF-03 API check failed: " + rootMessage(error));
                        feedback.setText("API error: " + rootMessage(error));
                        return;
                    }
                    setApiState("READY");
                    clientLog.info("IF-03 API ready: " + result.build().application()
                            + " " + result.build().version());
                    feedback.setText("API ready");
                }));
    }

    private void setApiState(String state) {
        apiBoundary.setDisable("CHECKING".equals(state));
        apiBoundary.setText("API :" + config.apiHttpPort() + "\n" + state);
    }

    private void setEventConnected(boolean connected, String state) {
        eventBoundary.setDisable(false);
        eventBoundary.setText("Events :" + config.eventPort() + "\n" + state);
    }

    private void setShellConnected(boolean connected, String state) {
        terminalBoundary.setDisable(false);
        terminalBoundary.setText("Terminal :" + config.shellPort() + "\n" + state);
        terminalInput.setDisable(!connected);
        terminalSend.setDisable(!connected);
    }

    private void setLogConnected(boolean connected, String state) {
        deviceLogBoundary.setDisable(false);
        deviceLogBoundary.setText(
                "Device log :" + config.loggingServerPort() + "\n" + state);
        deviceLogLevel.setDisable(!connected);
        applyDeviceLogLevel.setDisable(!connected);
        if (!connected) {
            currentDeviceLogLevel.setText("-");
        }
    }

    private void showAbout(Stage owner) {
        Alert about = new Alert(Alert.AlertType.INFORMATION);
        about.initOwner(owner);
        about.setTitle("About " + clientBuild.application());
        about.setHeaderText(clientBuild.application() + " — " + clientBuild.version());
        about.setContentText(
                "Version      : " + clientBuild.version() + System.lineSeparator()
                        + "Revision     : " + clientBuild.revision() + System.lineSeparator()
                        + "Source ref   : " + clientBuild.sourceRef() + System.lineSeparator()
                        + "Build origin : " + clientBuild.buildOrigin() + System.lineSeparator()
                        + "Source state : "
                        + (clientBuild.dirty() ? "modified" : "clean")
                        + System.lineSeparator()
                        + "Client config: " + configPath);
        about.showAndWait();
    }

    private static Tab tab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        return grid;
    }

    private static void addRow(GridPane grid, int row, String name, Label value) {
        grid.add(new Label(name), 0, row);
        grid.add(value, 1, row);
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

    @Override
    public void stop() {
        eventClient.close();
        shellClient.close();
        liveLogClient.close();
        requests.shutdownNow();
        if (clientLog != null) {
            clientLog.info("Development Client stopped");
            clientLog.close();
        }
    }
}
