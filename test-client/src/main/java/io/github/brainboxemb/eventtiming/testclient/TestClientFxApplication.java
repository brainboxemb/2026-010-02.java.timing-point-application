package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.testclient.central.CentralActions;
import io.github.brainboxemb.eventtiming.testclient.central.CentralClient;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;
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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.SVGPath;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
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
    private final Button centralBoundary = new Button();
    private final Button terminalBoundary = new Button();
    private final Button deviceLogBoundary = new Button();
    private final TextField targetHost = new TextField();
    private final Tooltip connectTargetTooltip = new Tooltip();
    private final Tooltip reconnectTargetTooltip = new Tooltip();
    private final Button connectTarget = new Button("Connect");
    private final Button reconnectTarget = new Button("Reconnect");

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
    // Batch high-volume log lines on FX without one Platform.runLater per line.
    private final FxTextAppender clientLogAppender = new FxTextAppender(clientLogs);
    private final FxTextAppender deviceLogAppender = new FxTextAppender(liveLogs);
    // The token is FX-thread-owned, the WebSocket listener may outlive the
    // connect attempt that originally registered it.
    private final ViewRequestEpoch eventConnectionEpoch = new ViewRequestEpoch();
    private final ViewRequestEpoch apiCheckEpoch = new ViewRequestEpoch();

    private ClientConfig config;
    private Path configPath;
    private EngineeringSystemContext system;
    private ClientLog clientLog;
    private ApiPane apiPane;
    private CentralClient centralClient;
    private EventTrafficPane eventTrafficPane;
    private HttpTrafficPane httpTrafficPane;
    private EngineeringWorkbench workbench;
    private BorderPane workbenchRoot;
    private Image windowIcon;

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
        VBox httpPanel = httpPane();
        VBox deviceLogPanel = deviceLogPane();
        VBox clientLogPanel = clientLogPane();

        centralClient =
                new CentralClient(
                        config.centralLocationIds(),
                        this::sendCentralTransport);
        CentralActions centralActions = centralClient.actions();
        apiPane = new ApiPane(
                this::client,
                requests,
                feedback::setText,
                this::setApiState,
                clientLog,
                centralActions);
        workbench = createWorkbench(
                deviceLogPanel,
                terminalPanel,
                clientLogPanel,
                eventsPanel,
                httpPanel);
        apiPane.setLogBookCountListener(workbench::setLogBookCount);
        apiPane.setRegistrationScopeListener(workbench::setRegistrationScope);
        apiPane.setSelectedNodeListener(workbench::setPerspectiveNodeId);
        apiPane.setSystemHost(system.host());
        workbench.setClientLogLevel(clientLog.level());

        MenuItem resetLayout = new MenuItem("Reset layout");
        resetLayout.setOnAction(event -> resetLayout(
                deviceLogPanel,
                terminalPanel,
                clientLogPanel,
                eventsPanel,
                httpPanel));
        Menu view = new Menu("View");
        view.getItems().add(resetLayout);

        MenuItem about = new MenuItem("About");
        about.setOnAction(event -> showAbout(stage));
        Menu help = new Menu("Help");
        help.getItems().add(about);
        MenuBar menuBar = new MenuBar(view, help);
        loadWindowIcon(stage);
        HBox hostBar = targetBar();
        HBox windowsCaption = null;
        boolean nativeCaption = WindowsIntegratedTitleBar.isEnabled();
        workbenchRoot = new BorderPane();
        if (nativeCaption) {
            // Keep app identity visible without returning to the old
            // right-edge overlap: icon / menu / flexible space / title /
            // flexible space / the library's 3 x 46 px caption buttons.
            Region beforeTitle = new Region();
            Region afterTitle = new Region();
            HBox.setHgrow(beforeTitle, Priority.ALWAYS);
            HBox.setHgrow(afterTitle, Priority.ALWAYS);
            Region systemControls = new Region();
            systemControls.setMinWidth(WindowsIntegratedTitleBar.CONTROLS_WIDTH);
            systemControls.setPrefWidth(WindowsIntegratedTitleBar.CONTROLS_WIDTH);
            systemControls.setMaxWidth(WindowsIntegratedTitleBar.CONTROLS_WIDTH);
            Label captionTitle = new Label(
                    clientBuild.application() + " — " + clientBuild.version());
            captionTitle.getStyleClass().add("engineering-caption-title");
            captionTitle.setMinWidth(0);
            captionTitle.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            windowsCaption = new HBox(
                    windowCaptionMark(), menuBar, beforeTitle,
                    captionTitle, afterTitle, systemControls);
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
                stage, windowsCaption, menuBar, reason ->
                        clientLog.warn("Windows titlebar integration: " + reason))) {
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

    /** One source icon for IDE/JavaFX taskbar and main-window caption. */
    private void loadWindowIcon(Stage stage) {
        try (InputStream stream = TestClientFxApplication.class
                .getResourceAsStream("/icons/event-timing.png")) {
            if (stream == null) {
                clientLog.warn("Engineering Client icon resource is missing");
                return;
            }
            windowIcon = new Image(stream);
            if (windowIcon.isError()) {
                clientLog.warn("Engineering Client icon image could not be loaded");
                return;
            }
            stage.getIcons().add(windowIcon);
        } catch (java.io.IOException error) {
            clientLog.warn("Unable to load Engineering Client icon: " + error);
        }
    }

    private javafx.scene.Node windowCaptionMark() {
        if (windowIcon == null || windowIcon.isError()) {
            return new Region();
        }
        ImageView image = new ImageView(windowIcon);
        image.setFitWidth(18);
        image.setFitHeight(18);
        image.setPreserveRatio(true);
        StackPane mark = new StackPane(image);
        mark.getStyleClass().add("engineering-caption-mark");
        mark.setMouseTransparent(true);
        return mark;
    }

    private EngineeringWorkbench createWorkbench(
            VBox deviceLogPanel,
            VBox terminalPanel,
            VBox clientLogPanel,
            VBox eventsPanel,
            VBox httpPanel) {
        return new EngineeringWorkbench(
                apiPane.systemsPane(),
                centralClient.nodesPane(),
                centralClient.locationsPane(),
                centralClient.messagesPane(),
                centralClient.logBookPane(),
                centralClient.raceDataPane(),
                centralClient.timingDataPane(),
                apiPane.timingNodePane(),
                apiPane.apiInfoPane(),
                apiPane.registrationPane(),
                apiPane.simulationPane(),
                apiPane.scriptsPane(),
                deviceLogPanel,
                terminalPanel,
                clientLogPanel,
                apiPane.registrationsPane(),
                apiPane.logBookPane(),
                apiPane.rawDataPane(),
                eventsPanel,
                httpPanel,
                apiPane::showAllRegistrations,
                apiPane::setShowAllRegistrations,
                apiPane::canLoadAllLogBook,
                apiPane::loadAllLogBookLabel,
                apiPane::loadAllLogBook);
    }

    private void resetLayout(
            VBox deviceLogPanel,
            VBox terminalPanel,
            VBox clientLogPanel,
            VBox eventsPanel,
            VBox httpPanel) {
        workbench.release();
        workbench = createWorkbench(
                deviceLogPanel,
                terminalPanel,
                clientLogPanel,
                eventsPanel,
                httpPanel);
        workbenchRoot.setCenter(workbench.root());
        workbench.setClientLogLevel(clientLog.level());
        workbench.setDeviceLogLevel(system.liveLogClient().isConnected()
                ? currentDeviceLogLevel.getText() : null);
        apiPane.setLogBookCountListener(workbench::setLogBookCount);
        apiPane.setRegistrationScopeListener(workbench::setRegistrationScope);
        apiPane.setSelectedNodeListener(workbench::setPerspectiveNodeId);
        feedback.setText("Default window layout restored");
    }

    private Path resolveConfigPath() {
        String specified = getParameters().getNamed().get("config");
        return specified == null || specified.isBlank()
                ? ClientConfig.defaultPath()
                : Path.of(specified);
    }

    private HBox targetBar() {
        Label hostLabel = new Label("Host");
        hostLabel.setLabelFor(targetHost);
        targetHost.setText(system.host());
        targetHost.setPromptText("Host or IP");
        targetHost.setPrefColumnCount(15);
        targetHost.setTooltip(new Tooltip(
                "Target host/IP from " + configPath
                        + ". Disconnect before switching an active target."));
        targetHost.setOnAction(event -> {
            if (hasStatefulConnection()
                    && !targetHost.getText().trim().equals(system.host())) {
                feedback.setText("Disconnect before changing Host");
                return;
            }
            toggleTargetConnection();
        });

        connectTarget.setOnAction(event -> toggleTargetConnection());
        connectTarget.getStyleClass().addAll("primary-connect", "connection-button");
        // A small vector power icon with a readable Connect/Disconnect caption.
        // No platform-dependent Unicode symbols or icon-only hidden actions.
        SVGPath power = new SVGPath();
        power.setContent("M 8 1.5 V 8 M 4.1 4.2 A 5.5 5.5 0 1 0 11.9 4.2");
        power.setFill(Color.TRANSPARENT);
        power.setStrokeWidth(1.6);
        power.setStrokeLineCap(StrokeLineCap.ROUND);
        power.getStyleClass().add("connect-power-icon");
        connectTarget.setGraphic(power);
        connectTarget.setTooltip(connectTargetTooltip);

        reconnectTarget.setOnAction(event -> reconnectMissingTargetLinks());
        reconnectTarget.getStyleClass().addAll(
                "secondary-reconnect",
                "connection-button");
        reconnectTarget.setTooltip(reconnectTargetTooltip);

        Label separator = new Label();
        separator.getStyleClass().add("host-toolbar-divider");
        HBox bar = new HBox(
                6, hostLabel, targetHost, connectTarget, reconnectTarget,
                separator, apiBoundary, eventBoundary, centralBoundary,
                terminalBoundary, deviceLogBoundary);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("host-toolbar");
        HBox.setHgrow(targetHost, Priority.NEVER);
        bar.setPadding(new Insets(3, 8, 3, 8));
        for (Button boundary : new Button[] {
                apiBoundary, eventBoundary, centralBoundary,
                terminalBoundary, deviceLogBoundary }) {
            boundary.getStyleClass().add("connection-button");
        }
        refreshTargetConnectButton();
        return bar;
    }

    private boolean hasStatefulConnection() {
        return system.eventClient().isConnected()
                || system.shellClient().isConnected()
                || system.liveLogClient().isConnected();
    }

    /** Global Connect/Disconnect controls only stateful IF-03/engineering links.
     * IF-03 HTTP is stateless and remains an independent reachability check. */
    private void toggleTargetConnection() {
        if (hasStatefulConnection()) {
            disconnectTarget();
        } else {
            connectTarget();
        }
    }

    private void connectTarget() {
        if (!applyTargetHost()) return;
        checkApi();
        if (!system.eventClient().isConnected() && !eventBoundary.isDisabled()) connectEvents();
        if (!system.shellClient().isConnected() && !terminalBoundary.isDisabled()) connectShell();
        if (!system.liveLogClient().isConnected() && !deviceLogBoundary.isDisabled()) connectLogs();
        refreshTargetConnectButton();
    }

    /**
     * Recovers only missing ordinary target links.
     *
     * <p>This deliberately leaves already-connected links untouched. In particular,
     * Device Log may survive SI-01 {@code reboot +log} while Events and Terminal
     * reconnect to the freshly composed runtime. Central remains an explicit role
     * switch and is never promoted by this action.</p>
     */
    private void reconnectMissingTargetLinks() {
        if (!targetHost.getText().trim().equals(system.host())) {
            feedback.setText(
                    "Disconnect before changing Host; Reconnect uses "
                            + system.host());
            return;
        }

        checkApi();
        if (!system.eventClient().isConnected()
                && !eventBoundary.isDisabled()) {
            connectEvents();
        }
        if (!system.shellClient().isConnected()
                && !terminalBoundary.isDisabled()) {
            connectShell();
        }
        if (!system.liveLogClient().isConnected()
                && !deviceLogBoundary.isDisabled()) {
            connectLogs();
        }

        feedback.setText(
                "Reconnecting missing interfaces to "
                        + system.host());
        clientLog.info(
                "Reconnecting missing Engineering Client interfaces");
        refreshTargetConnectButton();
    }

    private void disconnectTarget() {
        if (system.eventClient().isConnected()) disconnectEvents();
        if (system.shellClient().isConnected()) {
            system.shellClient().disconnect();
            setShellConnected(false, "CONNECT");
        }
        if (system.liveLogClient().isConnected()) {
            system.liveLogClient().disconnect();
            setLogConnected(false, "CONNECT");
        }
        feedback.setText("Disconnected external interfaces from " + system.host());
        clientLog.info("Disconnected Engineering Client external interfaces");
        refreshTargetConnectButton();
    }

    private void refreshTargetConnectButton() {
        boolean events = system.eventClient().isConnected();
        boolean terminal = system.shellClient().isConnected();
        boolean deviceLog = system.liveLogClient().isConnected();
        ConnectionToolbarState state =
                ConnectionToolbarState.of(events, terminal, deviceLog);
        int connected =
                ConnectionToolbarState.countConnected(events, terminal, deviceLog);
        boolean busy = eventBoundary.isDisabled()
                || centralBoundary.isDisabled()
                || terminalBoundary.isDisabled() || deviceLogBoundary.isDisabled();

        connectTarget.getStyleClass().removeAll(
                "connection-ok", "connection-busy", "connection-partial", "connection-error");
        if (busy) connectTarget.getStyleClass().add("connection-busy");
        else if (state == ConnectionToolbarState.ALL) {
            connectTarget.getStyleClass().add("connection-ok");
        } else if (state == ConnectionToolbarState.PARTIAL) {
            connectTarget.getStyleClass().add("connection-partial");
        }
        connectTarget.setText(busy
                ? "Connecting…" : state == ConnectionToolbarState.NONE ? "Connect" : "Disconnect");
        connectTarget.setDisable(busy);
        String action = state == ConnectionToolbarState.NONE
                ? "Apply Host and connect API check, Events, Terminal and Device Log"
                : "Disconnect all active Events, Terminal and Device Log links";
        connectTargetTooltip.setText(action
                + " (" + connected + "/3 stateful links connected)."
                + " The local Client Log is always active.");
        connectTarget.setAccessibleText(connectTarget.getText()
                + ". " + connected + " of 3 stateful links connected.");

        boolean reconnectAvailable =
                state.reconnectAvailable()
                        && !busy;
        reconnectTarget.setDisable(!reconnectAvailable);
        reconnectTargetTooltip.setText(
                state == ConnectionToolbarState.PARTIAL
                        ? "Reconnect only missing Events, Terminal and Device Log links; "
                                + "keep active links untouched (" + connected + "/3 connected)."
                        : "Reconnect becomes available when only some ordinary links are connected.");
        reconnectTarget.setAccessibleText(
                "Reconnect missing interfaces. "
                        + connected
                        + " of 3 stateful links connected.");
    }

    private void configureBoundaryButtons() {
        setApiState("CHECK");
        setEventConnected(false, "CONNECT");
        setCentralConnected(false, "CONNECT");
        setShellConnected(false, "CONNECT");
        setLogConnected(false, "CONNECT");

        apiBoundary.setOnAction(event -> checkApi());
        eventBoundary.setOnAction(event -> {
            if (system.eventClient().isConnected()) {
                disconnectEvents();
            } else {
                connectEvents();
            }
        });
        centralBoundary.setOnAction(event -> {
            if (system.eventClient().isUpstreamRoleConnected()) {
                disconnectCentral();
            } else {
                connectCentral();
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
        eventTrafficPane = new EventTrafficPane();
        return eventTrafficPane.pane();
    }

    private VBox httpPane() {
        httpTrafficPane = new HttpTrafficPane(system.httpTraffic());
        system.httpTraffic().subscribe(
                () -> Platform.runLater(httpTrafficPane::refresh));
        return httpTrafficPane.pane();
    }

    private VBox terminalPane() {
        terminal.setEditable(false);
        terminal.setWrapText(false);
        terminal.getStyleClass().add("engineering-console");
        VBox.setVgrow(terminal, Priority.ALWAYS);

        terminalInput.setPromptText("command");
        terminalInput.setDisable(true);
        terminalSend.setDisable(true);
        HBox.setHgrow(terminalInput, Priority.ALWAYS);
        HBox input = new HBox(8, terminalInput, terminalSend);
        input.setAlignment(Pos.CENTER_LEFT);

        terminalSend.setOnAction(event -> sendShellCommand());
        terminalInput.setOnAction(event -> sendShellCommand());

        VBox pane = new VBox(4, terminal, input);
        pane.getStyleClass().add("engineering-tool-pane");
        pane.setPadding(new Insets(3));
        return pane;
    }

    private VBox deviceLogPane() {
        deviceLogLevel.setDisable(true);
        applyDeviceLogLevel.setDisable(true);
        deviceLogLevel.getItems().setAll("TRACE", "DEBUG", "INFO", "WARN", "ERROR");
        deviceLogLevel.setValue("INFO");


        liveLogs.setEditable(false);
        liveLogs.setWrapText(false);
        liveLogs.getStyleClass().add("engineering-console");

        applyDeviceLogLevel.setOnAction(event -> applyDeviceLogLevel());

        liveLogs.setContextMenu(logContextMenu(liveLogs, true));
        VBox pane = new VBox(liveLogs);
        pane.getStyleClass().add("engineering-log-pane");
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
        clientLogs.getStyleClass().add("engineering-console");
        clientLog.subscribe(clientLogAppender::append);

        applyClientLogLevel.setOnAction(event -> applyClientLogLevel());

        clientLogs.setContextMenu(logContextMenu(clientLogs, false));
        VBox pane = new VBox(clientLogs);
        pane.getStyleClass().add("engineering-log-pane");
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
        if (system.eventClient().isConnected()) {
            setEventConnected(true, "CONNECTED");
            return;
        }
        connectEventSession(false);
    }

    /**
     * Promotes the one IF-03 event session to the explicit upstream role.
     *
     * <p>A normal Events/API connection is deliberately not a Central connection. The
     * Timing Point Application sees an upstream peer only while this role-specific
     * session is active.</p>
     */
    private void connectCentral() {
        if (system.eventClient().isUpstreamRoleConnected()) {
            setCentralConnected(true, "CONNECTED");
            return;
        }

        eventConnectionEpoch.invalidate();
        if (system.eventClient().isConnected()) {
            system.eventClient().disconnect();
        }
        setBoundaryState(eventBoundary, "Events", "CONNECTING");
        setBoundaryState(centralBoundary, "Central", "CONNECTING");
        connectEventSession(true);
    }

    /**
     * Drops the upstream role but keeps ordinary IF-03 Events connected.
     *
     * <p>The same ApiEventClient is reconnected without role=upstream; there is never a
     * second simultaneous WebSocket service.</p>
     */
    private void disconnectCentral() {
        if (!system.eventClient().isUpstreamRoleConnected()) {
            centralClient.disconnected();
                        setCentralConnected(false, "CONNECT");
            return;
        }

        eventConnectionEpoch.invalidate();
        setBoundaryState(eventBoundary, "Events", "CONNECTING");
        setBoundaryState(centralBoundary, "Central", "DISCONNECTING");
        system.eventClient().disconnect();
        centralClient.disconnected();
                connectEventSession(false);
    }

    private void connectEventSession(boolean upstreamRole) {
        final long eventGeneration = eventConnectionEpoch.begin();
        eventBoundary.setDisable(true);
        centralBoundary.setDisable(true);
        setBoundaryState(eventBoundary, "Events", "CONNECTING");
        if (upstreamRole) {
            setBoundaryState(centralBoundary, "Central", "CONNECTING");
        }
        java.net.URI endpoint =
                upstreamRole ? upstreamEventEndpoint() : eventEndpoint();
        clientLog.info(
                "Connecting IF-03 "
                        + (upstreamRole ? "Events + Central upstream" : "Events")
                        + " to "
                        + endpoint);

        try {
            system.eventClient().connect(endpoint, new ApiEventClient.Listener() {
                @Override
                public void onConnected() {
                    Platform.runLater(() -> {
                        if (!eventConnectionEpoch.isCurrent(eventGeneration)) return;
                        setEventConnected(true, "CONNECTED");
                        if (upstreamRole) {
                            centralClient.connected();
                            setCentralConnected(true, "CONNECTED");
                        } else {
                            centralClient.disconnected();
                            setCentralConnected(false, "CONNECT");
                        }
                                                apiPane.connected();
                        clientLog.info(
                                upstreamRole
                                        ? "IF-03 Events + Central upstream connected"
                                        : "IF-03 Events connected");
                    });
                }

                @Override
                public void onEvent(ApiEventClient.ApiEvent event) {
                    Platform.runLater(() -> {
                        if (eventConnectionEpoch.isCurrent(eventGeneration)) {
                            showEvent(event);
                        }
                    });
                }

                @Override
                public void onClosed(int statusCode, String reason) {
                    Platform.runLater(() -> {
                        if (!eventConnectionEpoch.isCurrent(eventGeneration)) return;
                        eventConnectionEpoch.invalidate();
                        setEventConnected(false, "CONNECT");
                        centralClient.disconnected();
                                                setCentralConnected(false, "CONNECT");
                        apiPane.disconnected(true);
                        clientLog.warn(
                                "IF-03 event session disconnected (" + statusCode + ")");
                    });
                }

                @Override
                public void onError(String message) {
                    Platform.runLater(() -> {
                        if (!eventConnectionEpoch.isCurrent(eventGeneration)) return;
                        clientLog.error("IF-03 Events error: " + message);
                        if (!system.eventClient().isConnected()) {
                            setEventConnected(false, "ERROR");
                            centralClient.disconnected();
                                                        setCentralConnected(false, upstreamRole ? "ERROR" : "CONNECT");
                            apiPane.disconnected(true);
                        }
                        feedback.setText("Events error: " + message);
                    });
                }
            }).whenComplete((ignored, error) -> {
                if (error != null) {
                    Platform.runLater(() -> {
                        if (!eventConnectionEpoch.isCurrent(eventGeneration)) return;
                        Throwable cause = rootCause(error);
                        setEventConnected(false, "ERROR");
                        centralClient.disconnected();
                                                setCentralConnected(false, upstreamRole ? "ERROR" : "CONNECT");
                        apiPane.disconnected(true);
                        clientLog.error(
                                "IF-03 event connect failed: " + rootMessage(cause));
                        feedback.setText("Events error: " + rootMessage(cause));
                    });
                }
            });
        } catch (RuntimeException ex) {
            setEventConnected(false, "CONNECT");
            centralClient.disconnected();
                        setCentralConnected(false, upstreamRole ? "ERROR" : "CONNECT");
            clientLog.error("IF-03 event connect failed: " + ex.getMessage());
        }
    }

    private void disconnectEvents() {
        eventConnectionEpoch.invalidate();
        setBoundaryState(eventBoundary, "Events", "DISCONNECTING");
        if (system.eventClient().isUpstreamRoleConnected()) {
            setBoundaryState(centralBoundary, "Central", "DISCONNECTING");
        }
        system.eventClient().disconnect();
        setEventConnected(false, "CONNECT");
        centralClient.disconnected();
                setCentralConnected(false, "CONNECT");
        apiPane.disconnected(true);
        clientLog.info("IF-03 Events disconnected");
    }

    private void showEvent(ApiEventClient.ApiEvent event) {
        eventTrafficPane.recordInbound(event);

        // Feed the presentation model but show only unmodified source JSON.
        // Duplicating four status labels here is redundant with Systems.
        if (event instanceof ApiEventClient.StatusEvent statusEvent) {
            apiPane.applyStatusEvent(statusEvent);
        } else if (event instanceof ApiEventClient.TimingDataEvent dataEvent) {
            apiPane.applyTimingDataEvent(dataEvent);
        } else if (event instanceof ApiEventClient.UpstreamDataEvent upstreamEvent
                && system.eventClient().isUpstreamRoleConnected()) {
            try {
                centralClient.accept(
                        upstreamEvent.upstreamData(),
                        upstreamEvent.occurredAt(),
                        upstreamEvent.receivedAt(),
                        upstreamEvent.rawPayloadJson());
                if (upstreamEvent.upstreamData().family()
                        == UpstreamData.Family.RECV) {
                    apiPane.refreshRegistrationProjectionFromServer();
                }
            } catch (RuntimeException ex) {
                clientLog.error(
                        "Central upstream data error: " + rootMessage(ex));
                feedback.setText(
                        "Central error: " + rootMessage(ex));
            }
        }

    }

    private void sendCentralTransport(UpstreamData data) {
        final long packetNr = data.packetNr();
        try {
            system.eventClient()
                    .sendUpstreamData(data)
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            Platform.runLater(() -> {
                                clientLog.error(
                                        "Central packet #"
                                                + packetNr
                                                + " send failed: "
                                                + rootMessage(error));
                                feedback.setText(
                                        "Central packet send failed: "
                                                + rootMessage(error));
                                centralClient.refresh();
                            });
                        }
                    });
        } catch (RuntimeException ex) {
            centralClient.refresh();
            throw ex;
        }
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
                                        deviceLogAppender.append(entry.formatted());
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

    private java.net.URI upstreamEventEndpoint() {
        return system.upstreamEventEndpoint();
    }

    private boolean applyTargetHost() {
        try {
            boolean changed = system.changeHost(targetHost.getText());
            targetHost.setText(system.host());
            if (!changed) {
                return true;
            }

            eventConnectionEpoch.invalidate();
            apiCheckEpoch.invalidate();
            setEventConnected(false, "CONNECT");
            centralClient.resetTargetState();
                        setCentralConnected(false, "CONNECT");
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
        final long checkGeneration = apiCheckEpoch.begin();
        final String target = system.host();
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
                    if (!apiCheckEpoch.isCurrent(checkGeneration)
                            || !target.equals(system.host())) return;
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
        refreshTargetConnectButton();
    }

    private void setApiState(String state) {
        apiBoundary.setDisable("CHECKING".equals(state));
        setBoundaryState(apiBoundary, "API", state);
    }

    private void setEventConnected(boolean connected, String state) {
        eventBoundary.setDisable(false);
        setBoundaryState(eventBoundary, "Events", state);
    }

    private void setCentralConnected(boolean connected, String state) {
        centralBoundary.setDisable(false);
        setBoundaryState(centralBoundary, "Central", state);
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
        if (apiPane != null) {
            apiPane.close();
        }
        if (centralClient != null) {
            centralClient.close();
        }
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
