package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Coordinates TimingNode controls/history and exposes framework-neutral JavaFX panes. */
final class TimingPane {
    private static final int INITIAL_LOGBOOK_ROWS = 100;
    private static final DateTimeFormatter CLOCK_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss[.SS]");
    private static final ZoneId INPUT_ZONE = ZoneId.systemDefault();

    private final Supplier<ApiClient> clientSupplier;
    private final ExecutorService requests;
    private final Consumer<String> rawSink;
    private final Consumer<String> feedback;
    private final Consumer<String> apiState;
    private final ClientLog clientLog;
    private final TimingViewModel model = new TimingViewModel();

    private final Label historyState = new Label("NOT SYNCED — connect Events");
    private final Button syncViewButton = new Button("Sync view");
    private final TreeItem<String> systemRoot = new TreeItem<>("System");
    private final TreeView<String> systemTree = new TreeView<>(systemRoot);
    private final Map<TreeItem<String>, String> treeNodeIds = new IdentityHashMap<>();
    private final VBox systemsPane = new VBox(6);
    private boolean updatingTreeSelection;
    private String systemHost = "-";
    private Consumer<Long> logBookCountListener = ignored -> {};
    private boolean eventsConnected;
    private boolean operationBusy;
    private boolean historyRefreshQueued;
    private long lastReportedLogBookCount = Long.MIN_VALUE;

    private final ComboBox<String> node = new ComboBox<>();
    private final Label problem = new Label("-");
    private final TextField locationInput = new TextField();
    private final Button open = new Button("Open");
    private final Button close = new Button("Close");

    private final Label lastOperation = new Label("-");
    private final Label autoRegCapability = new Label("Capability not loaded");
    private final TextField registrationPrefix = new TextField();
    private final TextField registrationNumber = new TextField("0001");
    private final TextField registrationDate = new TextField();
    private final TextField registrationTime = new TextField();
    private final Button now = new Button("Now");
    private final Button manualReg = new Button("Add manual");
    private final Button autoReg = new Button("Direct auto-reg");
    private static final String NODE_TIME = "TimingNode clock";
    private static final String PROVIDED_TIME = "Provided time";
    private final ComboBox<String> directTimeMode = new ComboBox<>();
    private final ComboBox<String> registrationScope = new ComboBox<>();

    private boolean updatingRegistrationTime;
    private String manualTimeSource = "AUTO";

    private final SimulatedTagsPane simulationPane;
    private final javafx.scene.Node timingNodePane;
    private final javafx.scene.Node registrationPane;
    private final javafx.scene.Node registrationsPane;
    private final javafx.scene.Node logBookPane;

    private final TableView<TimingViewModel.InterpretedRegistration> registrations =
            new TableView<>();

    private final TableView<ApiClient.TimingDataInfo> logBook = new TableView<>();
    private final FollowLatestTable<TimingViewModel.InterpretedRegistration> registrationFollow =
            new FollowLatestTable<>(registrations);
    private final FollowLatestTable<ApiClient.TimingDataInfo> logBookFollow =
            new FollowLatestTable<>(logBook);

    private boolean updatingNodeSelection;
    private final List<ApiEventClient.ApiEvent> bufferedEvents = new ArrayList<>();

    TimingPane(
            Supplier<ApiClient> clientSupplier,
            ExecutorService requests,
            String initialPrefix,
            javafx.scene.Node rightHeader,
            Consumer<String> rawSink,
            Consumer<String> feedback,
            Consumer<String> apiState,
            ClientLog clientLog) {
        if (clientSupplier == null || requests == null
                || rightHeader == null
                || rawSink == null || feedback == null
                || apiState == null || clientLog == null) {
            throw new IllegalArgumentException("TimingPane dependencies must not be null");
        }
        this.clientSupplier = clientSupplier;
        this.requests = requests;
        this.rawSink = rawSink;
        this.feedback = feedback;
        this.apiState = apiState;
        this.clientLog = clientLog;


        systemTree.setShowRoot(true);
        systemRoot.setExpanded(true);
        systemTree.getStyleClass().add("systems-tree");
        systemTree.setCellFactory(view -> new javafx.scene.control.TreeCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
        systemTree.getSelectionModel().selectedItemProperty().addListener(
                (ignored, oldItem, selectedItem) -> {
                    if (updatingTreeSelection) return;
                    String selectedId = treeNodeIds.get(selectedItem);
                    if (selectedId != null && !selectedId.equals(model.selectedNodeId())) {
                        model.selectNode(selectedId);
                        loadSelectedLogBook();
                    }
                });
        VBox.setVgrow(systemTree, Priority.ALWAYS);
        syncViewButton.setMaxWidth(Double.MAX_VALUE);
        historyState.getStyleClass().add("sync-state");
        systemsPane.getChildren().setAll(historyState, systemTree, syncViewButton);
        systemsPane.setPadding(new Insets(8));

        node.setPrefWidth(230);
        locationInput.setPrefColumnCount(8);
        GridPane nodeGrid = new GridPane();
        nodeGrid.setHgap(12);
        nodeGrid.setVgap(8);
        nodeGrid.setPadding(new Insets(10));
        add(nodeGrid, 0, "TimingNode", node);
        add(nodeGrid, 1, "Problem", problem);

        HBox locationRow = new HBox(
                8,
                new Label("LocationId"),
                locationInput,
                open,
                close);
        nodeGrid.add(locationRow, 0, 2, 2, 1);
        nodeGrid.add(
                new HBox(8, new Label("Last API operation"), lastOperation),
                0,
                3,
                2,
                1);
        timingNodePane = nodeGrid;

        registrationPrefix.setText(initialPrefix == null ? "" : initialPrefix);
        registrationPrefix.setPrefColumnCount(6);
        registrationNumber.setPrefColumnCount(10);
        registrationDate.setPrefColumnCount(12);
        registrationTime.setPrefColumnCount(10);
        registrationTime.setTooltip(new Tooltip(
                "Local civil time in " + INPUT_ZONE.getId()
                        + ". Manual registration time is sent in UTC;"
                        + " direct auto-reg chooses its own time source below."));
        updateNow();
        registrationDate.textProperty().addListener(
                (ignored, previous, value) ->
                        markRegistrationTimeManual());
        registrationTime.textProperty().addListener(
                (ignored, previous, value) ->
                        markRegistrationTimeManual());

        // One compact input form, no status dashboard between editable fields.
        // All fields are shared by manual and accepted direct registrations.
        registrationPrefix.setPrefWidth(92);
        registrationNumber.setPrefWidth(94);
        registrationDate.setPrefWidth(112);
        registrationTime.setPrefWidth(102);

        HBox registrationIdRow = new HBox(
                8, new Label("RegistrationId"), registrationPrefix, registrationNumber);
        registrationIdRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox dateTimeRow = new HBox(
                8, new Label("Local time"), registrationDate, registrationTime, now);
        dateTimeRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Tooltip.install(dateTimeRow, new Tooltip(
                "Date/time in " + INPUT_ZONE.getId()
                        + ". Now uses AUTO for manual registration;"
                        + " editing it marks the manual time as MAN."));

        directTimeMode.getItems().setAll(NODE_TIME, PROVIDED_TIME);
        directTimeMode.setValue(NODE_TIME);
        directTimeMode.setPrefWidth(152);
        directTimeMode.setTooltip(new Tooltip(
                "TimingNode clock omits time from IF-03. Provided time sends"
                        + " the visible date/time for deterministic replay."));
        autoReg.setTooltip(new Tooltip(
                "Injects an accepted registration directly at the TimingNode,"
                        + " bypassing antenna/tag/decoding/filtering."
                        + " Use Simulation to exercise the observation path."));
        HBox directControls = new HBox(8,
                new Label("Direct time"), directTimeMode, autoReg);
        directControls.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        autoRegCapability.managedProperty().bind(autoRegCapability.visibleProperty());
        autoRegCapability.getStyleClass().add("input-warning");
        manualReg.setTooltip(new Tooltip(
                "Manual time source is AUTO after Now and MAN after edits."
                        + " This value is sent in the manual registration request."));

        VBox registrationBox = new VBox(9,
                registrationIdRow, dateTimeRow, manualReg, directControls,
                autoRegCapability);
        registrationBox.getStyleClass().add("registration-form");
        registrationBox.setPadding(new Insets(10));
        registrationPane = registrationBox;

        simulationPane =
                new SimulatedTagsPane(
                        clientSupplier,
                        requests,
                        model::selectedNodeId,
                        rawSink,
                        feedback,
                        apiState,
                        this::handleCommandError,
                        this::refreshControls,
                        clientLog);

        configureRegistrationView();
        registrationScope.setItems(
                FXCollections.observableArrayList(
                        "Current location",
                        "All"));
        registrationScope.setValue(
                "Current location");
        registrationScope.setOnAction(
                event -> refreshLogBook());
        VBox interpretedBox = new VBox(
                6,
                new HBox(
                        8,
                        new Label("Show"),
                        registrationScope,
                        new Label("Times shown in " + INPUT_ZONE.getId())),
                registrations);
        VBox.setVgrow(registrations, Priority.ALWAYS);
        VBox upperRight =
                new VBox(
                        10,
                        rightHeader,
                        interpretedBox);
        VBox.setVgrow(
                interpretedBox,
                Priority.ALWAYS);
        registrationsPane = upperRight;

        configureLogBook();
        VBox historyBox = new VBox(6, logBook);
        VBox.setVgrow(logBook, Priority.ALWAYS);
        logBookPane = historyBox;

        syncViewButton.setTooltip(new Tooltip(
                "Reload current status, capabilities and LogBook history, "
                        + "then reconcile buffered live events."));
        syncViewButton.setOnAction(event -> syncView());

        node.setOnAction(event -> {
            if (updatingNodeSelection) {
                return;
            }
            String selected = node.getValue();
            if (selected != null && !selected.equals(model.selectedNodeId())) {
                model.selectNode(selected);
                loadSelectedLogBook();
            }
        });

        open.setOnAction(event -> open());
        close.setOnAction(event -> runStateCommand(
                api -> api.close(requireSelectedNode())));
        now.setOnAction(event -> updateNow());
        manualReg.setOnAction(event -> manualReg());
        autoReg.setOnAction(event -> autoReg());

        refresh();
    }

    javafx.scene.Node systemsPane() {
        return systemsPane;
    }

    void setSystemHost(String host) {
        systemHost = host;
        refreshTree();
    }

    void setLogBookCountListener(Consumer<Long> listener) {
        logBookCountListener = listener == null ? ignored -> {} : listener;
        lastReportedLogBookCount = model.logBookCount();
        logBookCountListener.accept(lastReportedLogBookCount);
    }

    javafx.scene.Node timingNodePane() {
        return timingNodePane;
    }

    javafx.scene.Node registrationPane() {
        return registrationPane;
    }

    javafx.scene.Node simulationPane() {
        return simulationPane;
    }

    javafx.scene.Node registrationsPane() {
        return registrationsPane;
    }

    javafx.scene.Node logBookPane() {
        return logBookPane;
    }

    void connected() {
        eventsConnected = true;
        syncView();
    }

    void disconnected(boolean stale) {
        eventsConnected = false;
        simulationPane.stopBatch();
        bufferedEvents.clear();
        model.viewState(stale
                ? TimingViewModel.ViewState.STALE
                : TimingViewModel.ViewState.DISCONNECTED);
        historyState.setText(
                stale
                        ? "STALE — reconnect Events"
                        : "NOT SYNCED — connect Events");
        refresh();
    }

    void applyStatus(ApiClient.StatusResult status) {
        if (model.applyStatus(status)) refresh();
    }

    void applyStatusEvent(ApiEventClient.StatusEvent event) {
        if (model.viewState() == TimingViewModel.ViewState.SYNCING) {
            bufferedEvents.add(event);
            return;
        }

        if ("STATUS_SNAPSHOT".equals(event.eventType())
                && model.viewState() != TimingViewModel.ViewState.LIVE) {
            connected();
            return;
        }

        if (model.viewState() != TimingViewModel.ViewState.LIVE) {
            return;
        }

        applyStatus(event.status());
    }

    void applyTimingDataEvent(ApiEventClient.TimingDataEvent event) {
        if (model.viewState() == TimingViewModel.ViewState.SYNCING) {
            bufferedEvents.add(event);
            return;
        }
        if (model.viewState() != TimingViewModel.ViewState.LIVE) {
            return;
        }

        if (model.mergeCommitted(event.timingData())) requestHistoryRefresh();
    }

    /**
     * A committed event should repaint the two history views, not the
     * Explorer, simulated-tag controls or the node selector. Several
     * IF-03 events in the same FX turn share one history update.
     */
    private void requestHistoryRefresh() {
        if (historyRefreshQueued) return;
        historyRefreshQueued = true;
        Platform.runLater(() -> {
            if (!historyRefreshQueued) return; // A full refresh superseded it.
            historyRefreshQueued = false;
            refreshLogBook();
        });
    }

    void syncView() {
        if (!eventsConnected) {
            model.viewState(TimingViewModel.ViewState.DISCONNECTED);
            historyState.setText("NOT SYNCED — connect Events");
            feedback.accept("Connect Events before synchronising the Timing view");
            refresh();
            return;
        }

        bufferedEvents.clear();
        model.viewState(TimingViewModel.ViewState.SYNCING);
        historyState.setText("SYNCING");
        feedback.accept("Synchronising API history...");
        refresh();

        final String preferredNode = model.selectedNodeId();
        final Long cachedLatest = model.latestSequence();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        ApiClient api = clientSupplier.get();
                        ApiClient.StatusResult status = api.getStatus();
                        ApiClient.CapabilitiesResult capabilities = api.getCapabilities();
                        String target = chooseNode(status, preferredNode);
                        if (target == null) {
                            return new SyncResult(
                                    status,
                                    capabilities,
                                    null,
                                    List.of(),
                                    true);
                        }

                        ApiClient.LogBookInfo info = api.getLogBookInfo(target);
                        boolean sameNode = target.equals(preferredNode);
                        boolean canAppendGap = sameNode
                                && cachedLatest != null
                                && info.last() != null
                                && cachedLatest.longValue() <= info.last().longValue();

                        List<ApiClient.LogBookPage> pages = new ArrayList<>();
                        boolean replace;
                        if (canAppendGap) {
                            replace = false;
                            long from = cachedLatest.longValue() + 1L;
                            while (info.last() != null && from <= info.last().longValue()) {
                                int limit = (int) Math.min(
                                        1000L,
                                        info.last().longValue() - from + 1L);
                                ApiClient.LogBookPage page =
                                        api.getLogBookFrom(target, from, limit);
                                pages.add(page);
                                if (page.next() == null) {
                                    break;
                                }
                                from = page.next().longValue();
                            }
                        } else {
                            replace = true;
                            if (info.count() > 0L) {
                                int last = (int) Math.min(
                                        INITIAL_LOGBOOK_ROWS,
                                        info.count());
                                pages.add(api.getLogBookLast(target, last));
                            }
                        }

                        return new SyncResult(
                                status,
                                capabilities,
                                info,
                                List.copyOf(pages),
                                replace);
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        model.viewState(TimingViewModel.ViewState.STALE);
                        historyState.setText("STALE — sync failed");
                        handleApiFailure("History sync", error);
                        refresh();
                        return;
                    }

                    apiState.accept("READY");
                    clientLog.info("API history synchronised");
                    model.applyStatus(result.status());
                    syncNodeChoice();
                    model.applyCapabilities(result.capabilities());
                    if (result.replace()) {
                        model.clearLogBook();
                    }
                    if (result.info() != null) {
                        model.applyLogBookInfo(result.info());
                    }
                    for (ApiClient.LogBookPage page : result.pages()) {
                        model.mergeLogBookPage(page);
                    }
                    applyBufferedEvents();
                    model.viewState(TimingViewModel.ViewState.LIVE);
                    historyState.setText("LIVE");
                    rawSink.accept(result.status().rawJson());
                    feedback.accept("OK");
                    refresh();
                }));
    }

    private void applyBufferedEvents() {
        for (ApiEventClient.ApiEvent event : bufferedEvents) {
            if (event instanceof ApiEventClient.StatusEvent statusEvent) {
                model.applyStatus(statusEvent.status());
            } else if (event instanceof ApiEventClient.TimingDataEvent timingDataEvent) {
                model.mergeCommitted(timingDataEvent.timingData());
            }
        }
        bufferedEvents.clear();
    }

    private void loadSelectedLogBook() {
        String selected = model.selectedNodeId();
        if (selected == null) {
            refresh();
            return;
        }

        bufferedEvents.clear();
        model.viewState(TimingViewModel.ViewState.SYNCING);
        historyState.setText("SYNCING");
        refresh();

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        ApiClient api = clientSupplier.get();
                        ApiClient.LogBookInfo info = api.getLogBookInfo(selected);
                        ApiClient.LogBookPage page = info.count() == 0L
                                ? null
                                : api.getLogBookLast(
                                        selected,
                                        (int) Math.min(INITIAL_LOGBOOK_ROWS, info.count()));
                        return new NodeLogBook(info, page);
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        model.viewState(TimingViewModel.ViewState.STALE);
                        historyState.setText("STALE");
                        handleApiFailure("LogBook load", error);
                    } else {
                        apiState.accept("READY");
                        model.clearLogBook();
                        model.applyLogBookInfo(result.info());
                        if (result.page() != null) {
                            model.mergeLogBookPage(result.page());
                            rawSink.accept(result.page().rawJson());
                        } else {
                            rawSink.accept(result.info().rawJson());
                        }
                        applyBufferedEvents();
                        model.viewState(TimingViewModel.ViewState.LIVE);
                        historyState.setText("LIVE");
                    }
                    refresh();
                }));
    }

    private void open() {
        Integer value = locationInputValue();
        if (value == null) {
            return;
        }
        runStateCommand(api -> api.open(requireSelectedNode(), value.intValue()));
    }

    private Integer locationInputValue() {
        try {
            return Integer.valueOf(locationInput.getText().trim());
        } catch (NumberFormatException ex) {
            lastOperation.setText("LocationId must be an integer");
            return null;
        }
    }

    private void autoReg() {
        final String selected = requireSelectedNode();
        boolean provided = PROVIDED_TIME.equals(directTimeMode.getValue());
        RegistrationInput input = registrationInput(provided);
        if (input == null) return;

        // The choice is independent from whether the shared manual-entry
        // date/time has been edited or Now was pressed.
        final String explicitTime = provided ? input.time() : null;
        final String source = provided ? "API" : "NODE";
        setOperationBusy(true);
        lastOperation.setText("Submitting direct auto-reg (" + source + ")...");
        feedback.accept("Injecting " + input.registrationId()
                + " on node " + selected + " · timeSrc=" + source);
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        ApiClient api = clientSupplier.get();
                        ApiClient.AutoRegResult result =
                                api.autoReg(
                                        selected,
                                        input.registrationId(),
                                        explicitTime);
                        ApiClient.LogBookPage page =
                                api.getLogBookFrom(selected, result.seq(), 1);
                        ApiClient.StatusResult status = api.getStatus();
                        return new AutoRegCommand(result, page, status);
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    setOperationBusy(false);
                    if (error != null) {
                        clientLog.warn("Direct auto-reg rejected/unknown: node="
                                + selected + ", id=" + input.registrationId()
                                + ", timeSrc=" + source);
                        handleCommandError(error);
                        return;
                    }
                    apiState.accept("READY");
                    rawSink.accept(result.result().rawJson());
                    clientLog.info("Direct auto-reg accepted: node=" + selected
                            + ", id=" + input.registrationId() + ", tagSrc=API"
                            + ", timeSrc=" + source + ", seq=" + result.result().seq());
                    lastOperation.setText("Accepted seq " + result.result().seq()
                            + " · node " + selected + " · " + source);
                    boolean statusChanged = model.applyStatus(result.status());
                    boolean historyChanged = model.mergeLogBookPage(result.page());
                    feedback.accept("Direct auto-reg accepted: seq "
                            + result.result().seq() + " · " + source);
                    refreshAfterCommand(statusChanged, historyChanged);
                }));
    }

    private void manualReg() {
        final String selected = requireSelectedNode();
        RegistrationInput input = registrationInput();
        if (input == null) {
            return;
        }
        final String timeSource =
                manualTimeSource;

        setOperationBusy(true);
        lastOperation.setText("Submitting...");
        feedback.accept("Submitting manual registration...");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        ApiClient api = clientSupplier.get();
                        ApiClient.CommitResult result =
                                api.manualRegistration(
                                        selected,
                                        input.registrationId(),
                                        input.time(),
                                        timeSource);
                        ApiClient.LogBookPage page =
                                api.getLogBookFrom(
                                        selected,
                                        result.seq(),
                                        1);
                        ApiClient.StatusResult status =
                                api.getStatus();
                        return new ManualRegCommand(
                                result,
                                page,
                                status);
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    setOperationBusy(false);
                    if (error != null) {
                        handleCommandError(error);
                        return;
                    }
                    apiState.accept("READY");
                    rawSink.accept(
                            result.result().rawJson());
                    clientLog.info(
                            "API manual registration committed as seq "
                                    + result.result().seq());
                    lastOperation.setText(
                            "seq "
                                    + result.result().seq());
                    boolean statusChanged = model.applyStatus(result.status());
                    boolean historyChanged = model.mergeLogBookPage(result.page());
                    feedback.accept("OK");
                    refreshAfterCommand(statusChanged, historyChanged);
                }));
    }

    private RegistrationInput registrationInput() {
        return registrationInput(true);
    }

    private RegistrationInput registrationInput(boolean requireTime) {
        String number =
                registrationNumber.getText()
                        .trim();
        if (!number.matches("[0-9]+")) {
            lastOperation.setText(
                    "Registration number must contain digits only");
            return null;
        }

        String id =
                registrationPrefix.getText()
                        .trim()
                        + number;
        if (!requireTime) return new RegistrationInput(id, null);
        try {
            String time =
                    TimingViewModel.canonicalTime(
                            LocalDate.parse(
                                    registrationDate.getText()
                                            .trim()),
                            LocalTime.parse(
                                    registrationTime.getText()
                                            .trim(),
                                    CLOCK_TIME),
                            INPUT_ZONE);
            return new RegistrationInput(
                    id,
                    time);
        } catch (RuntimeException ex) {
            lastOperation.setText(
                    "Date/time must use YYYY-MM-DD and HH:mm:ss.SS");
            return null;
        }
    }

    private void revokeRegistration(
            TimingViewModel.InterpretedRegistration registration) {
        ApiClient.TimingDataInfo source =
                model.registrationSource(
                        registration);
        if (source == null) {
            lastOperation.setText(
                    "Original ADD record is not available in the local view");
            return;
        }

        final String selected =
                requireSelectedNode();
        setOperationBusy(true);
        lastOperation.setText("Revoking...");
        feedback.accept("Submitting registration revoke...");

        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        ApiClient api =
                                clientSupplier.get();
                        ApiClient.CommitResult result =
                                api.revokeRegistration(
                                        selected,
                                        source);
                        ApiClient.LogBookPage page =
                                api.getLogBookFrom(
                                        selected,
                                        result.seq(),
                                        1);
                        ApiClient.StatusResult status =
                                api.getStatus();
                        return new RevokeCommand(
                                result,
                                page,
                                status);
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    setOperationBusy(false);
                    if (error != null) {
                        handleCommandError(error);
                        return;
                    }
                    apiState.accept("READY");
                    rawSink.accept(
                            result.result().rawJson());
                    clientLog.info(
                            "API registration revoke committed as seq "
                                    + result.result().seq());
                    lastOperation.setText(
                            "REV seq "
                                    + result.result().seq());
                    boolean statusChanged = model.applyStatus(result.status());
                    boolean historyChanged = model.mergeLogBookPage(result.page());
                    feedback.accept("OK");
                    refreshAfterCommand(statusChanged, historyChanged);
                }));
    }

    private void runStateCommand(StateCommand command) {
        setOperationBusy(true);
        lastOperation.setText("Requesting...");
        feedback.accept("Requesting TimingNode operation...");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        ApiClient api = clientSupplier.get();
                        ApiClient.OperationResult result = command.run(api);
                        ApiClient.StatusResult status = api.getStatus();
                        return new StateCommandResult(result, status);
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, requests)
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    setOperationBusy(false);
                    if (error != null) {
                        handleCommandError(error);
                        return;
                    }
                    apiState.accept("READY");
                    rawSink.accept(result.result().rawJson());
                    clientLog.info("API TimingNode operation result: "
                            + result.result().result());
                    lastOperation.setText(result.result().result());
                    boolean statusChanged = model.applyStatus(result.status());
                    feedback.accept("OK");
                    if (statusChanged) refresh();
                }));
    }

    private void handleCommandError(Throwable error) {
        Throwable root = rootCause(error);
        if (root instanceof ApiClient.ApiException apiError) {
            apiState.accept("READY");
            rawSink.accept(apiError.rawJson());
            clientLog.warn("API domain rejection " + apiError.code()
                    + ": " + apiError.getMessage());
            lastOperation.setText(apiError.code() + ": " + apiError.getMessage());
            feedback.accept("API result: " + apiError.code());
            if ("OUTCOME_UNKNOWN".equals(apiError.code())) {
                model.viewState(TimingViewModel.ViewState.STALE);
                historyState.setText("STALE");
                syncView();
            }
        } else {
            apiState.accept("UNREACHABLE");
            rawSink.accept(root.toString());
            clientLog.error("API request failed: " + rootMessage(error));
            lastOperation.setText("Error: " + rootMessage(error));
            feedback.accept("Error: " + rootMessage(error));
        }
        refresh();
    }

    private void handleApiFailure(String operation, Throwable error) {
        Throwable root = rootCause(error);
        if (root instanceof ApiClient.ApiException apiError) {
            apiState.accept("READY");
            rawSink.accept(apiError.rawJson());
        } else {
            apiState.accept("UNREACHABLE");
            rawSink.accept(root.toString());
        }
        clientLog.error(operation + " failed: " + rootMessage(error));
        lastOperation.setText(operation + " failed: " + rootMessage(error));
        feedback.accept("Error: " + rootMessage(error));
    }

    private void setOperationBusy(boolean busy) {
        // A concurrent status event must not re-enable controls while the
        // HTTP operation is still in progress.
        operationBusy = busy;
        refreshControls();
        systemTree.setDisable(busy || model.viewState() != TimingViewModel.ViewState.LIVE);
    }

    private void refreshAfterCommand(boolean statusChanged, boolean historyChanged) {
        if (statusChanged) refresh();
        else if (historyChanged) requestHistoryRefresh();
    }

    private void refresh() {
        historyRefreshQueued = false;
        syncNodeChoice();
        ApiClient.TimingNodeInfo selected = model.selectedNode();
        List<ApiClient.ProblemInfo> selectedProblems = model.selectedProblems();
        if (selectedProblems.isEmpty()) {
            problem.setText("-");
        } else {
            ApiClient.ProblemInfo first = selectedProblems.get(0);
            problem.setText(
                    first.severity()
                            + " "
                            + first.code()
                            + " — "
                            + first.message());
        }
        refreshControls();
        refreshTree();
        refreshLogBook();
    }

    private void refreshTree() {
        updatingTreeSelection = true;
        try {
            String title = "System · " + systemHost;
            if (!java.util.Objects.equals(systemRoot.getValue(), title)) {
                systemRoot.setValue(title);
            }
            Map<String, TreeItem<String>> existing = new java.util.HashMap<>();
            treeNodeIds.forEach((item, id) -> existing.put(id, item));
            treeNodeIds.clear();
            List<TreeItem<String>> children = new ArrayList<>();
            TreeItem<String> selectedItem = null;
            for (ApiClient.TimingNodeInfo nodeInfo : model.nodes()) {
                String label = nodeInfo.id() + " · " + nodeInfo.state()
                        + (nodeInfo.locationId() == null ? ""
                                : " · Location " + nodeInfo.locationId());
                List<ApiClient.ProblemInfo> warnings = model.problemsForNode(nodeInfo.id());
                if (!warnings.isEmpty()) {
                    label += " · " + warnings.get(0).severity()
                            + " " + warnings.get(0).code();
                }
                TreeItem<String> item = existing.getOrDefault(nodeInfo.id(), new TreeItem<>());
                if (!java.util.Objects.equals(item.getValue(), label)) {
                    item.setValue(label);
                }
                treeNodeIds.put(item, nodeInfo.id());
                children.add(item);
                if (nodeInfo.id().equals(model.selectedNodeId())) selectedItem = item;
            }
            if (!systemRoot.getChildren().equals(children)) {
                systemRoot.getChildren().setAll(children);
            }
            systemRoot.setExpanded(true);
            if (selectedItem != systemTree.getSelectionModel().getSelectedItem()) {
                if (selectedItem != null) systemTree.getSelectionModel().select(selectedItem);
                else systemTree.getSelectionModel().clearSelection();
            }
            systemTree.setDisable(operationBusy
                    || model.viewState() != TimingViewModel.ViewState.LIVE);
        } finally {
            updatingTreeSelection = false;
        }
    }

    private void refreshControls() {
        TimingViewModel.Controls controls = model.controls();
        boolean live = model.viewState() == TimingViewModel.ViewState.LIVE;
        // Only communicate an exceptional capability state. Normal operation
        // is self-explanatory from the available command buttons.
        autoRegCapability.setVisible(live && !model.autoRegEnabled());
        if (live && !model.autoRegEnabled()) {
            autoRegCapability.setText("Direct auto-reg unavailable on this system");
        }
        simulationPane.refresh(
                live,
                controls.simulation());
        node.setDisable(
                !live
                        || operationBusy
                        || model.nodes().size() <= 1
                        || simulationPane.running());
        locationInput.setDisable(!live || operationBusy || !controls.open());
        open.setDisable(!live || operationBusy || !controls.open());
        close.setDisable(!live || operationBusy || !controls.close());
        boolean registrationInputEnabled =
                live
                        && (controls.manualReg()
                                || controls.autoReg());
        registrationPrefix.setDisable(operationBusy || !registrationInputEnabled);
        registrationNumber.setDisable(operationBusy || !registrationInputEnabled);
        registrationDate.setDisable(operationBusy || !registrationInputEnabled);
        registrationTime.setDisable(operationBusy || !registrationInputEnabled);
        now.setDisable(operationBusy || !registrationInputEnabled);
        manualReg.setDisable(!live || operationBusy || !controls.manualReg());
        autoReg.setDisable(!live || operationBusy || !controls.autoReg());
        directTimeMode.setDisable(!live || operationBusy || !controls.autoReg());
        registrationScope.setDisable(!live || operationBusy);
        registrations.setDisable(!live || operationBusy);

        syncViewButton.setDisable(
                !eventsConnected || model.viewState() == TimingViewModel.ViewState.SYNCING);
    }

    private void refreshLogBook() {
        registrationFollow.update(model.interpretedRegistrations(
                INPUT_ZONE, "All".equals(registrationScope.getValue())));
        long count = model.logBookCount();
        if (count != lastReportedLogBookCount) {
            lastReportedLogBookCount = count;
            logBookCountListener.accept(count);
        }
        logBookFollow.update(model.records());
    }

    private void syncNodeChoice() {
        String selected = model.selectedNodeId();
        List<String> ids = model.nodes().stream()
                .map(ApiClient.TimingNodeInfo::id)
                .toList();
        if (node.getItems().equals(ids)
                && java.util.Objects.equals(node.getValue(), selected)) {
            return;
        }
        updatingNodeSelection = true;
        try {
            // JavaFX ComboBox.setItems() invalidates selection and can fire
            // change listeners; never replace identical node lists.
            if (!node.getItems().equals(ids)) {
                node.setItems(FXCollections.observableArrayList(ids));
            }
            if (!java.util.Objects.equals(node.getValue(), selected)) {
                node.setValue(selected);
            }
        } finally {
            updatingNodeSelection = false;
        }
    }

    private void configureRegistrationView() {
        TableColumn<TimingViewModel.InterpretedRegistration, String> time =
                registrationColumn(
                        "Time",
                        TimingViewModel.InterpretedRegistration::displayTime);
        TableColumn<TimingViewModel.InterpretedRegistration, String> type =
                registrationColumn(
                        "Type",
                        TimingViewModel.InterpretedRegistration::type);
        TableColumn<TimingViewModel.InterpretedRegistration, String> team =
                registrationColumn(
                        "TeamID",
                        value -> value.teamId() == null ? "-" : value.teamId());
        TableColumn<TimingViewModel.InterpretedRegistration, String> code =
                registrationColumn(
                        "Code",
                        TimingViewModel.InterpretedRegistration::code);
        TableColumn<TimingViewModel.InterpretedRegistration, Void> action =
                new TableColumn<>("");

        time.setPrefWidth(100);
        type.setPrefWidth(80);
        team.setPrefWidth(110);
        code.setPrefWidth(80);
        action.setMinWidth(48);
        action.setPrefWidth(48);
        action.setMaxWidth(48);
        action.setResizable(false);

        action.setCellFactory(column -> new TableCell<>() {
            private final Button button = new Button("🗑");
            private final Label deleted = new Label("DELETED");
            {
                button.setAccessibleText("Delete registration");
                button.setTooltip(new Tooltip(
                        "Delete appends a REV record; the original ADD remains "
                                + "in the technical LogBook."));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                TimingViewModel.InterpretedRegistration registration =
                        (TimingViewModel.InterpretedRegistration) getTableRow().getItem();
                if (!registration.deleted()) {
                    button.setOnAction(
                            event -> revokeRegistration(
                                    registration));
                }
                setGraphic(
                        registration.deleted()
                                ? deleted
                                : button);
            }
        });

        registrations.getColumns().setAll(
                time,
                type,
                team,
                code,
                action);
        registrations.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY);
        registrations.setPlaceholder(new Label("No registrations"));
        registrations.setPrefHeight(220);
        registrations.setRowFactory(table -> new TableRow<>() {
            @Override
            protected void updateItem(
                    TimingViewModel.InterpretedRegistration item,
                    boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && item.deleted()
                        ? "-fx-opacity: 0.55;"
                        : "");
            }
        });
    }

    private static TableColumn<TimingViewModel.InterpretedRegistration, String>
            registrationColumn(
                    String title,
                    java.util.function.Function<
                            TimingViewModel.InterpretedRegistration,
                            String> value) {
        TableColumn<TimingViewModel.InterpretedRegistration, String> column =
                new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue() == null
                        ? "" : value.apply(cell.getValue())));
        return column;
    }

    private void configureLogBook() {
        TableColumn<ApiClient.TimingDataInfo, String> seq = column(
                "Seq",
                value -> Long.toString(value.sequenceNumber()));
        TableColumn<ApiClient.TimingDataInfo, String> type = column(
                "Type",
                ApiClient.TimingDataInfo::recordType);
        TableColumn<ApiClient.TimingDataInfo, String> code = column(
                "Code",
                value -> String.join("/", value.codes()));
        TableColumn<ApiClient.TimingDataInfo, String> loc = column(
                "Location",
                value -> Integer.toString(value.locationId()));
        TableColumn<ApiClient.TimingDataInfo, String> reg = column(
                "RegistrationId",
                ApiClient.TimingDataInfo::registrationId);
        TableColumn<ApiClient.TimingDataInfo, String> effective = column(
                "Effective",
                ApiClient.TimingDataInfo::effectiveTime);
        TableColumn<ApiClient.TimingDataInfo, String> recorded = column(
                "Recorded",
                ApiClient.TimingDataInfo::recordedAt);

        seq.setPrefWidth(65);
        type.setPrefWidth(95);
        code.setPrefWidth(85);
        loc.setPrefWidth(80);
        reg.setPrefWidth(130);
        effective.setPrefWidth(235);
        recorded.setPrefWidth(235);
        logBook.getColumns().setAll(seq, type, code, loc, reg, effective, recorded);
        logBook.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        logBook.setPlaceholder(new Label("No committed records"));
        logBook.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> {
                    if (selected != null) {
                        rawSink.accept(selected.rawJson());
                    }
                });
    }

    private void updateNow() {
        updatingRegistrationTime = true;
        try {
            registrationDate.setText(
                    LocalDate.now(INPUT_ZONE)
                            .toString());
            registrationTime.setText(
                    DateTimeFormatter.ofPattern(
                            "HH:mm:ss.SS")
                            .format(
                                    LocalTime.now(
                                            INPUT_ZONE)));
        } finally {
            updatingRegistrationTime = false;
        }
        manualTimeSource = "AUTO";
        manualReg.setText("Add manual [AUTO]");
    }

    private void markRegistrationTimeManual() {
        if (updatingRegistrationTime) {
            return;
        }
        manualTimeSource = "MAN";
        manualReg.setText("Add manual [MAN]");
    }

    private static TableColumn<ApiClient.TimingDataInfo, String> column(
            String title,
            java.util.function.Function<ApiClient.TimingDataInfo, String> value) {
        TableColumn<ApiClient.TimingDataInfo, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue() == null
                        ? "" : value.apply(cell.getValue())));
        return column;
    }

    private String requireSelectedNode() {
        String value = model.selectedNodeId();
        if (value == null) {
            throw new IllegalStateException("No TimingNode selected");
        }
        return value;
    }

    private static String chooseNode(
            ApiClient.StatusResult status,
            String preferredNode) {
        if (preferredNode != null) {
            for (ApiClient.TimingNodeInfo node : status.nodes()) {
                if (preferredNode.equals(node.id())) {
                    return preferredNode;
                }
            }
        }
        return status.nodes().isEmpty() ? null : status.nodes().get(0).id();
    }


    private static void add(GridPane grid, int row, String label, javafx.scene.Node value) {
        grid.add(new Label(label), 0, row);
        grid.add(value, 1, row);
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

    private record SyncResult(
            ApiClient.StatusResult status,
            ApiClient.CapabilitiesResult capabilities,
            ApiClient.LogBookInfo info,
            List<ApiClient.LogBookPage> pages,
            boolean replace) {
    }

    private record NodeLogBook(
            ApiClient.LogBookInfo info,
            ApiClient.LogBookPage page) {
    }

    private record StateCommandResult(
            ApiClient.OperationResult result,
            ApiClient.StatusResult status) {
    }

    private record RegistrationInput(
            String registrationId,
            String time) {
    }

    private record ManualRegCommand(
            ApiClient.CommitResult result,
            ApiClient.LogBookPage page,
            ApiClient.StatusResult status) {
    }

    private record AutoRegCommand(
            ApiClient.AutoRegResult result,
            ApiClient.LogBookPage page,
            ApiClient.StatusResult status) {
    }

    private record RevokeCommand(
            ApiClient.CommitResult result,
            ApiClient.LogBookPage page,
            ApiClient.StatusResult status) {
    }

    @FunctionalInterface
    private interface StateCommand {
        ApiClient.OperationResult run(ApiClient api) throws Exception;
    }
}
