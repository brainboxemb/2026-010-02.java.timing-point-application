package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** TimingNode controls/history inside the API-first workbench. */
final class TimingPane extends VBox {
    private static final int INITIAL_LOGBOOK_ROWS = 100;
    private static final DateTimeFormatter CLOCK_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");
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
    private final HBox syncStateBar = new HBox();
    private boolean eventsConnected;

    private final ComboBox<String> node = new ComboBox<>();
    private final Label state = new Label("-");
    private final Label location = new Label("-");
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
    private final Button autoReg = new Button("Send auto-reg");

    private final TableView<TimingViewModel.InterpretedRegistration> registrations =
            new TableView<>();
    private final Label logBookCount = new Label("0");
    private final TableView<ApiClient.TimingDataInfo> logBook = new TableView<>();

    private boolean updatingNodeSelection;
    private final List<ApiEventClient.ApiEvent> bufferedEvents = new ArrayList<>();

    TimingPane(
            Supplier<ApiClient> clientSupplier,
            ExecutorService requests,
            String initialPrefix,
            Consumer<String> rawSink,
            Consumer<String> feedback,
            Consumer<String> apiState,
            ClientLog clientLog) {
        if (clientSupplier == null || requests == null || rawSink == null
                || feedback == null || apiState == null || clientLog == null) {
            throw new IllegalArgumentException("TimingPane dependencies must not be null");
        }
        this.clientSupplier = clientSupplier;
        this.requests = requests;
        this.rawSink = rawSink;
        this.feedback = feedback;
        this.apiState = apiState;
        this.clientLog = clientLog;

        setSpacing(10);

        syncStateBar.setSpacing(8);
        syncStateBar.getChildren().setAll(
                new Label("Timing view"),
                historyState,
                syncViewButton);

        node.setPrefWidth(230);
        locationInput.setPrefColumnCount(8);
        GridPane nodeGrid = new GridPane();
        nodeGrid.setHgap(12);
        nodeGrid.setVgap(8);
        nodeGrid.setPadding(new Insets(10));
        add(nodeGrid, 0, "TimingNode", node);
        add(nodeGrid, 1, "State", state);
        add(nodeGrid, 2, "LocationId", location);
        add(nodeGrid, 3, "Problem", problem);

        HBox locationRow = new HBox(
                8,
                new Label("LocationId"),
                locationInput,
                open,
                close);
        nodeGrid.add(locationRow, 0, 4, 2, 1);
        nodeGrid.add(
                new HBox(8, new Label("Last API operation"), lastOperation),
                0,
                5,
                2,
                1);

        TitledPane nodePane = new TitledPane("Selected TimingNode", nodeGrid);
        nodePane.setCollapsible(false);

        registrationPrefix.setText(initialPrefix == null ? "" : initialPrefix);
        registrationPrefix.setPrefColumnCount(6);
        registrationNumber.setPrefColumnCount(10);
        registrationDate.setPrefColumnCount(12);
        registrationTime.setPrefColumnCount(10);
        registrationTime.setTooltip(new Tooltip(
                "Local civil time in " + INPUT_ZONE.getId()
                        + "; sent to IF-03 as canonical UTC."));
        updateNow();

        GridPane registrationGrid = new GridPane();
        registrationGrid.setHgap(10);
        registrationGrid.setVgap(8);
        add(registrationGrid, 0, "Prefix", registrationPrefix);
        add(registrationGrid, 1, "Number", registrationNumber);
        add(registrationGrid, 2, "Date", registrationDate);
        registrationGrid.add(
                new Label("Time (" + INPUT_ZONE.getId() + ")"),
                0,
                3);
        registrationGrid.add(
                new HBox(8, registrationTime, now),
                1,
                3);
        registrationGrid.add(autoReg, 1, 4);

        VBox registrationBox = new VBox(8, autoRegCapability, registrationGrid);
        registrationBox.setPadding(new Insets(10));
        TitledPane registrationPane =
                new TitledPane("Registration input", registrationBox);
        registrationPane.setCollapsible(false);

        configureRegistrationView();
        VBox interpretedBox = new VBox(
                6,
                new Label("Times shown in " + INPUT_ZONE.getId()),
                registrations);
        VBox.setVgrow(registrations, Priority.ALWAYS);
        TitledPane interpretedPane =
                new TitledPane("Registrations", interpretedBox);
        interpretedPane.setCollapsible(false);

        configureLogBook();
        VBox historyBox = new VBox(
                6,
                new HBox(8, new Label("Count"), logBookCount),
                logBook);
        VBox.setVgrow(logBook, Priority.ALWAYS);
        TitledPane historyPane =
                new TitledPane("LogBook / committed TimingData", historyBox);
        historyPane.setCollapsible(false);

        VBox left = new VBox(10, nodePane, registrationPane);
        left.setPrefWidth(390);
        left.setMinWidth(340);

        VBox right = new VBox(10, interpretedPane, historyPane);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(interpretedPane, Priority.SOMETIMES);
        VBox.setVgrow(historyPane, Priority.ALWAYS);

        HBox workbench = new HBox(12, left, right);
        HBox.setHgrow(right, Priority.ALWAYS);
        getChildren().add(workbench);
        VBox.setVgrow(workbench, Priority.ALWAYS);

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
        autoReg.setOnAction(event -> autoReg());

        refresh();
    }

    HBox syncStateBar() {
        return syncStateBar;
    }

    void connected() {
        eventsConnected = true;
        syncView();
    }

    void disconnected(boolean stale) {
        eventsConnected = false;
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
        model.applyStatus(status);
        syncNodeChoice();
        refresh();
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

        model.mergeCommitted(event.timingData());
        refreshLogBook();
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
                syncNodeChoice();
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
        final String number = registrationNumber.getText().trim();
        if (!number.matches("[0-9]+")) {
            lastOperation.setText("Registration number must contain digits only");
            return;
        }
        final String id = registrationPrefix.getText().trim() + number;
        final String time;
        try {
            time = TimingViewModel.canonicalTime(
                    LocalDate.parse(registrationDate.getText().trim()),
                    LocalTime.parse(registrationTime.getText().trim(), CLOCK_TIME),
                    INPUT_ZONE);
        } catch (RuntimeException ex) {
            lastOperation.setText("Date/time must use YYYY-MM-DD and HH:mm:ss");
            return;
        }

        setOperationBusy(true);
        lastOperation.setText("Submitting...");
        feedback.accept("Submitting registration...");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        ApiClient api = clientSupplier.get();
                        ApiClient.AutoRegResult result = api.autoReg(selected, id, time);
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
                        handleCommandError(error);
                        return;
                    }
                    apiState.accept("READY");
                    rawSink.accept(result.result().rawJson());
                    clientLog.info("API registration request committed as seq "
                            + result.result().seq());
                    lastOperation.setText("seq " + result.result().seq());
                    model.applyStatus(result.status());
                    model.mergeLogBookPage(result.page());
                    syncNodeChoice();
                    feedback.accept("OK");
                    refresh();
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
                    model.applyStatus(result.status());
                    syncNodeChoice();
                    feedback.accept("OK");
                    refresh();
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
        if (busy) {
            open.setDisable(true);
            close.setDisable(true);
            autoReg.setDisable(true);
        } else {
            refreshControls();
        }
    }

    private void refresh() {
        syncNodeChoice();
        ApiClient.TimingNodeInfo selected = model.selectedNode();
        state.setText(selected == null ? "-" : selected.state());
        location.setText(selected == null || selected.locationId() == null
                ? "-"
                : Integer.toString(selected.locationId()));
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
        refreshLogBook();
    }

    private void refreshControls() {
        TimingViewModel.Controls controls = model.controls();
        boolean live = model.viewState() == TimingViewModel.ViewState.LIVE;
        if (!live) {
            autoRegCapability.setText("Capability state cached/not synchronised");
        } else if (model.autoRegEnabled()) {
            autoRegCapability.setText("DIRECT_REGISTRATION_SIMULATION enabled");
        } else {
            autoRegCapability.setText("DIRECT_REGISTRATION_SIMULATION unavailable");
        }
        node.setDisable(!live || model.nodes().size() <= 1);
        locationInput.setDisable(!live || !controls.open());
        open.setDisable(!live || !controls.open());
        close.setDisable(!live || !controls.close());
        registrationPrefix.setDisable(!live || !controls.autoReg());
        registrationNumber.setDisable(!live || !controls.autoReg());
        registrationDate.setDisable(!live || !controls.autoReg());
        registrationTime.setDisable(!live || !controls.autoReg());
        now.setDisable(!live || !controls.autoReg());
        autoReg.setDisable(!live || !controls.autoReg());
        syncViewButton.setDisable(
                !eventsConnected || model.viewState() == TimingViewModel.ViewState.SYNCING);
    }

    private void refreshLogBook() {
        registrations.setItems(FXCollections.observableArrayList(
                model.interpretedRegistrations(INPUT_ZONE)));
        logBookCount.setText(Long.toString(model.logBookCount()));
        logBook.setItems(FXCollections.observableArrayList(model.records()));
    }

    private void syncNodeChoice() {
        String selected = model.selectedNodeId();
        List<String> ids = model.nodes().stream()
                .map(ApiClient.TimingNodeInfo::id)
                .toList();
        updatingNodeSelection = true;
        try {
            node.setItems(FXCollections.observableArrayList(ids));
            node.setValue(selected);
        } finally {
            updatingNodeSelection = false;
        }
    }

    private void configureRegistrationView() {
        TableColumn<TimingViewModel.InterpretedRegistration, String> time =
                registrationColumn(
                        "Time",
                        TimingViewModel.InterpretedRegistration::displayTime);
        TableColumn<TimingViewModel.InterpretedRegistration, String> registration =
                registrationColumn(
                        "RegistrationId",
                        TimingViewModel.InterpretedRegistration::registrationId);
        TableColumn<TimingViewModel.InterpretedRegistration, String> code =
                registrationColumn(
                        "Code",
                        TimingViewModel.InterpretedRegistration::code);
        TableColumn<TimingViewModel.InterpretedRegistration, String> status =
                registrationColumn(
                        "State",
                        TimingViewModel.InterpretedRegistration::state);
        TableColumn<TimingViewModel.InterpretedRegistration, Void> delete =
                new TableColumn<>("Delete");

        time.setPrefWidth(100);
        registration.setPrefWidth(150);
        code.setPrefWidth(85);
        status.setPrefWidth(100);
        delete.setPrefWidth(90);

        delete.setCellFactory(column -> new TableCell<>() {
            private final Button button = new Button("Delete");
            {
                button.setDisable(true);
                button.setTooltip(new Tooltip(
                        "Delete will append a REV record; the public REV operation "
                                + "is not available yet."));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : button);
            }
        });

        registrations.getColumns().setAll(
                time,
                registration,
                code,
                status,
                delete);
        registrations.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
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
                new ReadOnlyStringWrapper(value.apply(cell.getValue())));
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
        registrationDate.setText(LocalDate.now(INPUT_ZONE).toString());
        registrationTime.setText(CLOCK_TIME.format(LocalTime.now(INPUT_ZONE)));
    }

    private static TableColumn<ApiClient.TimingDataInfo, String> column(
            String title,
            java.util.function.Function<ApiClient.TimingDataInfo, String> value) {
        TableColumn<ApiClient.TimingDataInfo, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(value.apply(cell.getValue())));
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

    private record AutoRegCommand(
            ApiClient.AutoRegResult result,
            ApiClient.LogBookPage page,
            ApiClient.StatusResult status) {
    }

    @FunctionalInterface
    private interface StateCommand {
        ApiClient.OperationResult run(ApiClient api) throws Exception;
    }
}
