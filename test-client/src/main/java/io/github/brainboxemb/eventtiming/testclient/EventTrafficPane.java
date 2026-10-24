package io.github.brainboxemb.eventtiming.testclient;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
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

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only inspector for ordinary IF-03 WebSocket event traffic.
 *
 * <p>Central IF-06 UPSTREAM_DATA is deliberately excluded and is inspected through
 * Central Messages instead. Direction and type filters are presentation-only.</p>
 */
final class EventTrafficPane {
    enum Direction {
        IN,
        OUT
    }

    record Entry(
            Direction direction,
            Instant timestamp,
            String type,
            String rawPayload) {
        Entry {
            if (direction == null
                    || timestamp == null
                    || type == null
                    || type.isBlank()
                    || rawPayload == null) {
                throw new IllegalArgumentException(
                        "Traffic entry fields must not be null/blank");
            }
        }
    }

    private static final String ALL = "ALL";

    private final ObservableList<Entry> entries = FXCollections.observableArrayList();
    private final FilteredList<Entry> visible = new FilteredList<>(entries);
    private final ComboBox<String> directionFilter = new ComboBox<>();
    private final ComboBox<String> typeFilter = new ComboBox<>();
    private final TableView<Entry> table = new TableView<>(visible);
    private final TextArea rawFrame = new TextArea();
    private final VBox pane;

    EventTrafficPane() {
        directionFilter.getItems().setAll(ALL, Direction.IN.name(), Direction.OUT.name());
        directionFilter.setValue(ALL);
        directionFilter.setPrefWidth(92);
        directionFilter.setTooltip(new Tooltip(
                "IN = SI-01 to SI-02, OUT = SI-02 to SI-01"));

        typeFilter.getItems().setAll(ALL);
        typeFilter.setValue(ALL);
        typeFilter.setPrefWidth(190);

        directionFilter.setOnAction(event -> applyFilter());
        typeFilter.setOnAction(event -> applyFilter());

        configureTable();
        rawFrame.setEditable(false);
        rawFrame.setWrapText(false);
        rawFrame.setPromptText("Select a frame to inspect its exact payload");
        rawFrame.setPrefRowCount(5);
        rawFrame.getStyleClass().add("engineering-raw-data");
        table.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) ->
                        rawFrame.setText(selected == null ? "" : selected.rawPayload()));

        HBox filters = new HBox(
                8,
                new Label("Direction"),
                directionFilter,
                new Label("Type"),
                typeFilter);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.getStyleClass().add("engineering-traffic-filters");

        pane = new VBox(4, filters, table, rawFrame);
        pane.getStyleClass().add("engineering-data-pane");
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    VBox pane() {
        return pane;
    }

    void recordInbound(ApiEventClient.ApiEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        if (!isNodeEvent(event)) {
            return;
        }
        add(new Entry(
                Direction.IN,
                event.occurredAt(),
                typeOf(event),
                event.rawJson()));
    }

    private void add(Entry entry) {
        entries.add(entry);
        addType(entry.type());
    }

    private void addType(String type) {
        Set<String> values = new LinkedHashSet<>(typeFilter.getItems());
        if (values.add(type)) {
            String selected = typeFilter.getValue();
            typeFilter.getItems().setAll(values);
            typeFilter.setValue(selected == null ? ALL : selected);
        }
    }

    private void configureTable() {
        TableColumn<Entry, String> direction = column(
                "Dir",
                value -> value.direction().name());
        TableColumn<Entry, String> type = column(
                "Type",
                Entry::type);
        TableColumn<Entry, String> time = column(
                "Time",
                value -> value.timestamp().toString());
        TableColumn<Entry, String> payload = column(
                "Payload",
                Entry::rawPayload);

        direction.setMinWidth(48);
        direction.setPrefWidth(48);
        direction.setMaxWidth(48);
        direction.setResizable(false);
        type.setPrefWidth(165);
        time.setPrefWidth(205);
        payload.setPrefWidth(650);

        table.getColumns().setAll(direction, type, time, payload);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No matching WebSocket traffic"));
        table.getStyleClass().add("engineering-traffic-table");
    }

    private void applyFilter() {
        String direction = directionFilter.getValue();
        String type = typeFilter.getValue();
        visible.setPredicate(entry ->
                matches(entry, direction, type));
    }

    static boolean matches(
            Entry entry,
            String direction,
            String type) {
        if (entry == null) {
            return false;
        }

        String normalizedDirection =
                direction == null ? ALL : direction.trim().toUpperCase(Locale.ROOT);
        String normalizedType =
                type == null ? ALL : type.trim();

        boolean directionMatches =
                ALL.equals(normalizedDirection)
                        || entry.direction().name().equals(normalizedDirection);
        boolean typeMatches =
                ALL.equals(normalizedType)
                        || entry.type().equals(normalizedType);
        return directionMatches && typeMatches;
    }

    static boolean isNodeEvent(ApiEventClient.ApiEvent event) {
        return event != null
                && !(event instanceof ApiEventClient.UpstreamDataEvent);
    }

    static String typeOf(ApiEventClient.ApiEvent event) {
        return event.eventType();
    }

    private static TableColumn<Entry, String> column(
            String title,
            java.util.function.Function<Entry, String> value) {
        TableColumn<Entry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(
                        cell.getValue() == null ? "" : value.apply(cell.getValue())));
        return column;
    }
}
