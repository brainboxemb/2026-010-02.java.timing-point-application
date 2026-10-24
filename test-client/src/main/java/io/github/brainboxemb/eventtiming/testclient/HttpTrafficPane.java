package io.github.brainboxemb.eventtiming.testclient;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Read-only projection of IF-03 HTTP calls retained by the system context. */
final class HttpTrafficPane {
    private static final String ALL = "ALL";

    private final HttpTrafficJournal journal;
    private final ComboBox<String> methodFilter = new ComboBox<>();
    private final ComboBox<String> statusFilter = new ComboBox<>();
    private final TableView<ApiClient.HttpCall> table = new TableView<>();
    private final FollowLatestTable<ApiClient.HttpCall> follow =
            new FollowLatestTable<>(table);
    private final TextArea detail = new TextArea();
    private final VBox pane;
    private boolean rebuildingFilters;

    HttpTrafficPane(HttpTrafficJournal journal) {
        if (journal == null) {
            throw new IllegalArgumentException("journal must not be null");
        }
        this.journal = journal;

        methodFilter.getItems().setAll(ALL);
        methodFilter.setValue(ALL);
        methodFilter.setPrefWidth(100);
        methodFilter.setTooltip(new Tooltip("Filter by HTTP method"));

        statusFilter.getItems().setAll(ALL);
        statusFilter.setValue(ALL);
        statusFilter.setPrefWidth(100);
        statusFilter.setTooltip(new Tooltip("Filter by HTTP status"));

        methodFilter.setOnAction(event -> {
            if (!rebuildingFilters) {
                refresh();
            }
        });
        statusFilter.setOnAction(event -> {
            if (!rebuildingFilters) {
                refresh();
            }
        });

        configureTable();

        detail.setEditable(false);
        detail.setWrapText(false);
        detail.setPromptText("Select an HTTP call to inspect request and response");
        detail.setPrefRowCount(6);
        detail.getStyleClass().add("engineering-raw-data");
        table.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) ->
                        detail.setText(selected == null ? "" : detailText(selected)));

        HBox filters = new HBox(
                8,
                new Label("Method"),
                methodFilter,
                new Label("Status"),
                statusFilter);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.getStyleClass().add("engineering-traffic-filters");

        pane = new VBox(4, filters, table, detail);
        pane.getStyleClass().add("engineering-data-pane");
        VBox.setVgrow(table, Priority.ALWAYS);

        refresh();
    }

    VBox pane() {
        return pane;
    }

    void refresh() {
        List<ApiClient.HttpCall> snapshot = journal.snapshot();
        rebuildFilterValues(snapshot);
        String method = methodFilter.getValue();
        String status = statusFilter.getValue();
        follow.update(
                snapshot.stream()
                        .filter(call -> matches(call, method, status))
                        .toList());
    }

    private void rebuildFilterValues(List<ApiClient.HttpCall> snapshot) {
        String selectedMethod = methodFilter.getValue();
        String selectedStatus = statusFilter.getValue();

        Set<String> methods = new LinkedHashSet<>();
        Set<String> statuses = new LinkedHashSet<>();
        methods.add(ALL);
        statuses.add(ALL);
        for (ApiClient.HttpCall call : snapshot) {
            methods.add(call.method().toUpperCase(Locale.ROOT));
            statuses.add(statusText(call));
        }

        rebuildingFilters = true;
        try {
            methodFilter.getItems().setAll(methods);
            statusFilter.getItems().setAll(statuses);
            methodFilter.setValue(
                    selectedMethod != null && methods.contains(selectedMethod)
                            ? selectedMethod : ALL);
            statusFilter.setValue(
                    selectedStatus != null && statuses.contains(selectedStatus)
                            ? selectedStatus : ALL);
        } finally {
            rebuildingFilters = false;
        }
    }

    private void configureTable() {
        TableColumn<ApiClient.HttpCall, String> method =
                column("Method", ApiClient.HttpCall::method);
        TableColumn<ApiClient.HttpCall, String> path =
                column("Path", ApiClient.HttpCall::path);
        TableColumn<ApiClient.HttpCall, String> time =
                column("Time", call -> ViewerTime.instant(call.startedAt(), true));
        TableColumn<ApiClient.HttpCall, String> status =
                column("Status", HttpTrafficPane::statusText);

        method.setMinWidth(72);
        method.setPrefWidth(72);
        method.setMaxWidth(72);
        path.setPrefWidth(560);
        time.setPrefWidth(205);
        status.setMinWidth(72);
        status.setPrefWidth(72);
        status.setMaxWidth(72);

        table.getColumns().setAll(method, path, time, status);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No matching HTTP traffic"));
        table.getStyleClass().add("engineering-traffic-table");
    }

    static boolean matches(
            ApiClient.HttpCall call,
            String method,
            String status) {
        if (call == null) {
            return false;
        }
        String normalizedMethod =
                method == null ? ALL : method.trim().toUpperCase(Locale.ROOT);
        String normalizedStatus =
                status == null ? ALL : status.trim().toUpperCase(Locale.ROOT);

        return (ALL.equals(normalizedMethod)
                        || call.method().equalsIgnoreCase(normalizedMethod))
                && (ALL.equals(normalizedStatus)
                        || statusText(call).equalsIgnoreCase(normalizedStatus));
    }

    static String statusText(ApiClient.HttpCall call) {
        return call.statusCode() == null
                ? "ERROR"
                : Integer.toString(call.statusCode().intValue());
    }

    static String detailText(ApiClient.HttpCall call) {
        StringBuilder text = new StringBuilder();
        text.append(call.method())
                .append(' ')
                .append(call.path())
                .append(System.lineSeparator())
                .append("Started: ")
                .append(ViewerTime.instant(call.startedAt(), true))
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Request")
                .append(System.lineSeparator())
                .append(call.requestBody() == null || call.requestBody().isEmpty()
                        ? "<no body>" : call.requestBody())
                .append(System.lineSeparator())
                .append(System.lineSeparator())
                .append("Response ")
                .append(statusText(call))
                .append(System.lineSeparator());

        if (call.responseBody() != null && !call.responseBody().isEmpty()) {
            text.append(call.responseBody());
        } else {
            text.append("<no body>");
        }
        if (call.failure() != null && !call.failure().isBlank()) {
            text.append(System.lineSeparator())
                    .append(System.lineSeparator())
                    .append("Failure")
                    .append(System.lineSeparator())
                    .append(call.failure());
        }
        return text.toString();
    }

    private static TableColumn<ApiClient.HttpCall, String> column(
            String title,
            java.util.function.Function<ApiClient.HttpCall, String> value) {
        TableColumn<ApiClient.HttpCall, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(
                        cell.getValue() == null ? "" : value.apply(cell.getValue())));
        return column;
    }
}
