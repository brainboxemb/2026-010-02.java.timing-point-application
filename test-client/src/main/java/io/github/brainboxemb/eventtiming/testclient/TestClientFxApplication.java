package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.Region;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
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
    private final Label clientLogBoundary = new Label();
    private final TextField targetHost = new TextField();
    private final Button connectTarget = new Button("Connect");

    private final TextArea eventLog = new TextArea();

    private final TextArea terminal = new TextArea();
    private final TextField terminalInput = new TextField();
    private final Button terminalSend = new Button("Send");

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
    private EngineeringSystemContext system;
    private ClientLog clientLog;
    private ApiPane apiPane;
    private EngineeringWorkbench workbench;
    private BorderPane workbenchRoot;

    @Override
    public void start(Stage stage) throws Exception {
        configPath = resolveConfigPath();
        config = ClientConfig.load(configPath);
        system = new EngineeringSystemContext(config);
        clientLog = ClientLog.open(config.clientLogPath(), config.clientLogLevel());
        clientLog.info("Engineering Client starting with config " + configPath);

        configureBoundaryButtons();

        VBox terminalPanel = terminalPane();
        VBox eventsPanel = eventsPane();
        VBox deviceLogPanel = deviceLogPane();
        VBox clientLogPanel = clientLogPane();

        apiPane = new ApiPane(
                this::client,
                requests,
                config.registrationPrefix(),
                feedback::setText,
                this::setApiState,
                clientLog);

        workbench = createWorkbench(
                deviceLogPanel, terminalPanel, clientLogPanel, eventsPanel);
        apiPane.setLogBookCountListener(workbench::setLogBookCount);
        apiPane.setSystemHost(system.host());
        workbench.setClientLogLevel(clientLog.level());

        MenuItem resetLayout = new MenuItem("Reset layout");
        resetLayout.setOnAction(event -> resetLayout(
                deviceLogPanel, terminalPanel, clientLogPanel, eventsPanel));
        Menu view = new Menu("View");
        view.getItems().add(resetLayout);

        MenuItem about = new MenuItem("About");
        about.setOnAction(event -> showAbout(stage));
        Menu help = new Menu("Help");
        help.getItems().add(about);
        MenuBar menuBar = new MenuBar(view, help);
        HBox hostBar = targetBar();
        HBox windowsCaption = null;
        boolean nativeCaption = WindowsIntegratedTitleBar.isEnabled();
        workbenchRoot = new BorderPane();
        if (nativeCaption) {
            // The menu and title occupy the same actual Windows caption row.
            // Keep network controls below, and leave room at the far right for
            // the native-looking minimize/maximize/close buttons.
            Region dragSpace = new Region();
            HBox.setHgrow(dragSpace, Priority.ALWAYS);
            Label captionTitle = new Label(
                    clientBuild.application() + " — " + clientBuild.version());
            captionTitle.getStyleClass().add("engineering-caption-title");
            windowsCaption = new HBox(menuBar, dragSpace, captionTitle);
            windowsCaption.setAlignment(Pos.CENTER_LEFT);
            windowsCaption.getStyleClass().add("engineering-native-caption");
            workbenchRoot.setTop(new VBox(windowsCaption, hostBar));
        } else {
            HBox top = new HBox(menuBar, hostBar);
            HBox.setHgrow(hostBar, Priority.ALWAYS);
            workbenchRoot.setTop(top);
        }
        workbenchRoot.setCenter(workbench.root());
        workbenchRoot.setBottom(feedback);
        BorderPane.setMargin(feedback, new Insets(0, 12, 12, 12));

        stage.setTitle(clientBuild.application() + " — " + clientBuild.version());
        Scene scene = new Scene(workbenchRoot, 1360, 850);
        var stylesheet = TestClientFxApplication.class.getResource("/engineering-client.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        stage.setScene(scene);
        stage.show();
        if (nativeCaption && !WindowsIntegratedTitleBar.install(
                stage, windowsCaption, menuBar)) {
            // Leave the original native frame available if the Windows-only
            // integration cannot initialize. No native change for other OSes.
            windowsCaption.getChildren().remove(menuBar);
            ((VBox) workbenchRoot.getTop()).getChildren().remove(hostBar);
            HBox fallback = new HBox(menuBar, hostBar);
            HBox.setHgrow(hostBar, Priority.ALWAYS);
            workbenchRoot.setTop(fallback);
            clientLog.warn("Windows titlebar extension unavailable, using standard frame");
        }
        clientLog.info("Engineering Client UI ready");
    }

    private EngineeringWorkbench createWorkbench(
            VBox deviceLogPanel, VBox terminalPanel, VBox clientLogPanel, VBox eventsPanel) {
        return new EngineeringWorkbench(
                apiPane.systemsPane(),
                apiPane.timingNodePane(),
                apiPane.registrationPane(),
                apiPane.simulationPane(),
                deviceLogPanel,
                terminalPanel,
                clientLogPanel,
                apiPane.registrationsPane(),
                apiPane.logBookPane(),
                apiPane.rawDataPane(),
                eventsPanel);
    }

    private void resetLayout(
            VBox deviceLogPanel, VBox terminalPanel, VBox clientLogPanel, VBox eventsPanel) {
        workbench.release();
        workbench = createWorkbench(deviceLogPanel, terminalPanel, clientLogPanel, eventsPanel);
        workbenchRoot.setCenter(workbench.root());
        workbench.setClientLogLevel(clientLog.level());
        workbench.setDeviceLogLevel(system.liveLogClient().isConnected()
                ? currentDeviceLogLevel.getText() : null);
        apiPane.setLogBookCountListener(workbench::setLogBookCount);
        feedback.setText("Default window layout restored");
    }

    private Path resolveConfigPath() {
        String specified = getParameters().getNamed().get("config");
        return specified == null || specified.isBlank()
                ? ClientConfig.defaultPath()
                : Path.of(specified);
    }

    private HBox targetBar() {
        targetHost.setText(system.host());
        targetHost.setPromptText("host or IP");
        targetHost.setPrefColumnCount(15);
        targetHost.setTooltip(new Tooltip(
                "Startup default from " + configPath
                        + ". Edit the host/IP and apply it for all external boundaries."));
        targetHost.setOnAction(event -> connectTarget());
        connectTarget.setOnAction(event -> connectTarget());
        connectTarget.getStyleClass().add("primary-connect");
        connectTarget.setTooltip(new Tooltip(
                "Apply host and connect all interfaces; click an individual option to toggle it."));

        apiBoundary.setTooltip(new Tooltip(
                "IF-03 HTTP is stateless. Click to check that the API at the active target is reachable."));
        clientLogBoundary.getStyleClass().add("boundary-status");

        HBox bar = new HBox(
                6,
                new Label("Host"),
                targetHost,
                connectTarget,
                apiBoundary,
                eventBoundary,
                terminalBoundary,
                deviceLogBoundary,
                clientLogBoundary);
        HBox.setHgrow(targetHost, Priority.NEVER);
        bar.setPadding(new Insets(5, 8, 5, 8));
        for (Button boundary : new Button[] {
                apiBoundary, eventBoundary, terminalBoundary, deviceLogBoundary }) {
            boundary.getStyleClass().add("connection-button");
        }
        return bar;
    }

    private void connectTarget() {
        if (!applyTargetHost()) return;
        checkApi();
        if (!system.eventClient().isConnected() && !eventBoundary.isDisabled()) connectEvents();
        if (!system.shellClient().isConnected() && !terminalBoundary.isDisabled()) connectShell();
        if (!system.liveLogClient().isConnected() && !deviceLogBoundary.isDisabled()) connectLogs();
    }

    private void configureBoundaryButtons() {
        setApiState("CHECK");
        setEventConnected(false, "CONNECT");
        setShellConnected(false, "CONNECT");
        setLogConnected(false, "CONNECT");
        clientLogBoundary.setText("● Client Log");

        apiBoundary.setOnAction(event -> checkApi());
        eventBoundary.setOnAction(event -> {
            if (system.eventClient().isConnected()) {
                disconnectEvents();
            } else {
                connectEvents();
            }
        });
        terminalBoundary.setOnAction(event -> {
            if (system.shellClient().isConnected()) {
                system.shellClient().disconnect();
            } else {
                connectShell();
            }
        });
        deviceLogBoundary.setOnAction(event -> {
            if (system.liveLogClient().isConnected()) {
                system.liveLogClient().disconnect();
            } else {
                connectLogs();
            }
        });
    }

    private VBox eventsPane() {
        eventLog.setEditable(false);
        eventLog.setWrapText(false);
        eventLog.getStyleClass().add("engineering-raw-data");
        VBox pane = new VBox(eventLog);
        pane.setPadding(new Insets(4));
        VBox.setVgrow(eventLog, Priority.ALWAYS);
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


        liveLogs.setEditable(false);
        liveLogs.setWrapText(false);
        liveLogs.setStyle(
                "-fx-control-inner-background: black;"
                        + "-fx-text-fill: #e8e8e8;"
                        + "-fx-font-family: 'Consolas';"
                        + "-fx-font-size: 12px;");

        applyDeviceLogLevel.setOnAction(event -> applyDeviceLogLevel());

        liveLogs.setContextMenu(logContextMenu(liveLogs, true));
        VBox pane = new VBox(liveLogs);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(liveLogs, Priority.ALWAYS);
        return pane;
    }

    private VBox clientLogPane() {
        clientLogLevel.getItems().setAll("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        clientLogLevel.setValue(clientLog.level());
        currentClientLogLevel.setText(clientLog.level());


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

        clientLogs.setContextMenu(logContextMenu(clientLogs, false));
        VBox pane = new VBox(clientLogs);
        pane.setPadding(new Insets(12));
        VBox.setVgrow(clientLogs, Priority.ALWAYS);
        return pane;
    }

    private ContextMenu logContextMenu(TextArea area, boolean device) {
        ContextMenu context = new ContextMenu();
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(event -> area.copy());
        MenuItem selectAll = new MenuItem("Select all");
        selectAll.setOnAction(event -> area.selectAll());
        Menu levels = new Menu("Log level");
        ToggleGroup choices = new ToggleGroup();
        for (String level : new String[] {"TRACE", "DEBUG", "INFO", "WARN", "ERROR"}) {
            RadioMenuItem choice = new RadioMenuItem(level);
            choice.setToggleGroup(choices);
            choice.setOnAction(event -> {
                if (device) {
                    deviceLogLevel.setValue(level);
                    applyDeviceLogLevel();
                } else {
                    clientLogLevel.setValue(level);
                    applyClientLogLevel();
                }
            });
            levels.getItems().add(choice);
        }
        context.setOnShowing(event -> {
            String current = device ? currentDeviceLogLevel.getText() : clientLog.level();
            levels.setDisable(device && !system.liveLogClient().isConnected());
            for (MenuItem item : levels.getItems()) {
                ((RadioMenuItem) item).setSelected(item.getText().equals(current));
            }
        });
        context.getItems().addAll(copy, selectAll, new SeparatorMenuItem(), levels);
        return context;
    }

    private void connectEvents() {
        eventBoundary.setDisable(true);
        setBoundaryState(eventBoundary, "Events", "CONNECTING");
        clientLog.info("Connecting IF-03 Events to " + eventEndpoint());

        try {
            system.eventClient().connect(eventEndpoint(), new ApiEventClient.Listener() {
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
                        if (!system.eventClient().isConnected()) {
                            setEventConnected(false, "ERROR");
                            apiPane.disconnected(true);
                        }
                        feedback.setText("Events error: " + message);
                    });
                }
            }).whenComplete((ignored, error) -> {
                if (error != null) {
                    Platform.runLater(() -> {
                        Throwable cause = rootCause(error);
                        setEventConnected(false, "ERROR");
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
        setBoundaryState(eventBoundary, "Events", "DISCONNECTING");
        system.eventClient().disconnect();
        if (!system.eventClient().isConnected()) {
            setEventConnected(false, "CONNECT");
            apiPane.disconnected(true);
            clientLog.info("IF-03 Events disconnected");
        }
    }

    private void showEvent(ApiEventClient.ApiEvent event) {
        // Feed the presentation model but show only unmodified source JSON.
        // Duplicating four status labels here is redundant with Systems.
        if (event instanceof ApiEventClient.StatusEvent statusEvent) {
            apiPane.applyStatusEvent(statusEvent);
        } else if (event instanceof ApiEventClient.TimingDataEvent dataEvent) {
            apiPane.applyTimingDataEvent(dataEvent);
        }

        if (!eventLog.getText().isEmpty()) {
            eventLog.appendText(System.lineSeparator());
        }
        eventLog.appendText(event.rawJson());
        eventLog.appendText(System.lineSeparator());
        eventLog.positionCaret(eventLog.getLength());
    }

    private void connectLogs() {
        deviceLogBoundary.setDisable(true);
        setBoundaryState(deviceLogBoundary, "Device Log", "CONNECTING");
        clientLog.info("Connecting SI-01 device log");

        CompletableFuture
                .runAsync(() -> {
                    try {
                        system.liveLogClient().connect(
                                system.host(),
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
                                            workbench.setDeviceLogLevel(level);
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
                        system.liveLogClient().requestLevel();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((ignored, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        Throwable cause = rootCause(error);
                        setLogConnected(false, "ERROR");
                        clientLog.error("SI-01 device log connect failed: "
                                + rootMessage(cause));
                        feedback.setText("Device log error: " + rootMessage(cause));
                    }
                }));
    }

    private void applyDeviceLogLevel() {
        try {
            system.liveLogClient().setLevel(deviceLogLevel.getValue());
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
            workbench.setClientLogLevel(clientLog.level());
            feedback.setText("Client log level " + clientLog.level());
            clientLog.info("Engineering Client log level changed to " + clientLog.level());
        } catch (RuntimeException ex) {
            feedback.setText("Client log error: " + ex.getMessage());
        }
    }

    private void appendDeviceLog(LiveLogClient.LogEntry entry) {
        liveLogs.appendText(entry.formatted());
        liveLogs.positionCaret(liveLogs.getLength());
    }

    private void connectShell() {
        terminalBoundary.setDisable(true);
        setBoundaryState(terminalBoundary, "Terminal", "CONNECTING");
        clientLog.info("Connecting Remote Shell");

        CompletableFuture
                .runAsync(() -> {
                    try {
                        system.shellClient().connect(
                                system.host(),
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
                        setShellConnected(false, "ERROR");
                        clientLog.error("Remote Shell connect failed: " + rootMessage(cause));
                        feedback.setText("Terminal error: " + rootMessage(cause));
                    } else {
                        setShellConnected(true, "CONNECTED");
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
            system.shellClient().send(command);
            clientLog.debug("Remote Shell command sent: " + command);
            terminalInput.clear();
        } catch (Exception ex) {
            clientLog.error("Remote Shell send failed: " + ex.getMessage());
            feedback.setText("Terminal error: " + ex.getMessage());
        }
    }

    private ApiClient client() {
        return system.apiClient();
    }

    private java.net.URI apiEndpoint() {
        return system.apiEndpoint();
    }

    private java.net.URI eventEndpoint() {
        return system.eventEndpoint();
    }

    private boolean applyTargetHost() {
        try {
            boolean changed = system.changeHost(targetHost.getText());
            targetHost.setText(system.host());
            if (!changed) {
                return true;
            }

            setEventConnected(false, "CONNECT");
            setShellConnected(false, "CONNECT");
            setLogConnected(false, "CONNECT");
            setApiState("CHECK");
            apiPane.setSystemHost(system.host());
            apiPane.disconnected(true);
            feedback.setText("Target changed to " + system.host());
            clientLog.info("Engineering Client target changed to " + system.host());
            return true;
        } catch (IllegalArgumentException ex) {
            targetHost.setText(system.host());
            feedback.setText(ex.getMessage());
            return false;
        }
    }

    private void checkApi() {
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

    private void setBoundaryState(Button button, String boundary, String state) {
        button.getStyleClass().removeAll(
                "connection-ok", "connection-busy", "connection-error");
        boolean on = "CONNECTED".equals(state) || "READY".equals(state);
        boolean busy = "CONNECTING".equals(state) || "CHECKING".equals(state)
                || "DISCONNECTING".equals(state);
        boolean error = "UNREACHABLE".equals(state) || "ERROR".equals(state);
        if (on) button.getStyleClass().add("connection-ok");
        else if (busy) button.getStyleClass().add("connection-busy");
        else if (error) button.getStyleClass().add("connection-error");
        button.setText(boundary);
        String action = switch (state) {
            case "CONNECTED" -> "Connected. Click to disconnect";
            case "READY" -> "API check succeeded. Click to recheck";
            case "CHECK" -> "Click to check API";
            case "CONNECT" -> "Disconnected. Click to connect";
            case "CHECKING", "CONNECTING", "DISCONNECTING" -> "Please wait";
            case "ERROR", "UNREACHABLE" -> "Connection failed. Click to retry";
            default -> "Status " + state;
        };
        button.setTooltip(new Tooltip(boundary + ": " + action));
        button.setAccessibleText(boundary + " " + state + ". " + action);
    }

    private void setApiState(String state) {
        apiBoundary.setDisable("CHECKING".equals(state));
        setBoundaryState(apiBoundary, "API", state);
    }

    private void setEventConnected(boolean connected, String state) {
        eventBoundary.setDisable(false);
        setBoundaryState(eventBoundary, "Events", state);
    }

    private void setShellConnected(boolean connected, String state) {
        terminalBoundary.setDisable(false);
        setBoundaryState(terminalBoundary, "Terminal", state);
        terminalInput.setDisable(!connected);
        terminalSend.setDisable(!connected);
    }

    private void setLogConnected(boolean connected, String state) {
        deviceLogBoundary.setDisable(false);
        setBoundaryState(deviceLogBoundary, "Device Log", state);
        deviceLogLevel.setDisable(!connected);
        applyDeviceLogLevel.setDisable(!connected);
        if (!connected) currentDeviceLogLevel.setText("-");
        if (workbench != null) {
            workbench.setDeviceLogLevel(connected ? currentDeviceLogLevel.getText() : null);
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
        if (system != null) {
            system.close();
        }
        requests.shutdownNow();
        if (clientLog != null) {
            clientLog.info("Engineering Client stopped");
            clientLog.close();
        }
    }
}
