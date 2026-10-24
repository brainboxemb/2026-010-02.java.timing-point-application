package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Wide semantic IF-06 message inspector for the Central System Simulator. */
final class CentralMessagesPane {
    private static final String ALL = "ALL";

    record MessageRow(
            CentralSystemSimulator.MessageView message,
            boolean followEnd) {
        MessageRow {
            if (followEnd == (message != null)) {
                throw new IllegalArgumentException(
                        "Row must be either one message or the follow-end marker");
            }
        }

        static MessageRow message(CentralSystemSimulator.MessageView message) {
            if (message == null) {
                throw new IllegalArgumentException("message must not be null");
            }
            return new MessageRow(message, false);
        }

        static MessageRow followEnd() {
            return new MessageRow(null, true);
        }
    }

    private final CentralSystemSimulator simulator;
    private final ObservableList<CentralSystemSimulator.MessageView> messages =
            FXCollections.observableArrayList();
    private final ObservableList<MessageRow> rows =
            FXCollections.observableArrayList();
    private final ComboBox<String> directionFilter = new ComboBox<>();
    private final ComboBox<String> nodeFilter = new ComboBox<>();
    private final ComboBox<String> typeFilter = new ComboBox<>();
    private final Button endButton = new Button("End");
    private final TableView<MessageRow> table = new TableView<>(rows);
    private final TextArea rawMessage = new TextArea();
    private final VBox pane;

    private boolean followEnd = true;
    private boolean rebuildingRows;

    CentralMessagesPane(CentralSystemSimulator simulator) {
        if (simulator == null) {
            throw new IllegalArgumentException("simulator must not be null");
        }
        this.simulator = simulator;

        directionFilter.getItems().setAll(ALL, "IN", "OUT");
        directionFilter.setValue(ALL);
        directionFilter.setPrefWidth(90);

        nodeFilter.getItems().setAll(ALL);
        nodeFilter.setValue(ALL);
        nodeFilter.setPrefWidth(105);

        typeFilter.getItems().setAll(ALL);
        typeFilter.setValue(ALL);
        typeFilter.setPrefWidth(155);

        directionFilter.setOnAction(event -> applyFilter());
        nodeFilter.setOnAction(event -> applyFilter());
        typeFilter.setOnAction(event -> applyFilter());

        endButton.setTooltip(new Tooltip("Follow new Central messages at the end"));
        endButton.setOnAction(event -> selectFollowEnd());

        configureTable();

        rawMessage.setEditable(false);
        rawMessage.setWrapText(false);
        rawMessage.setPromptText("Select a Central message to inspect its exact IF-06 JSON");
        rawMessage.setPrefRowCount(5);
        rawMessage.getStyleClass().add("engineering-raw-data");

        table.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> selectionChanged(selected));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox filters = new HBox(
                8,
                new Label("Direction"),
                directionFilter,
                new Label("Node"),
                nodeFilter,
                new Label("Type"),
                typeFilter,
                spacer,
                endButton);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.getStyleClass().add("engineering-traffic-filters");

        pane = new VBox(4, filters, table, rawMessage);
        pane.getStyleClass().add("engineering-data-pane");
        VBox.setVgrow(table, Priority.ALWAYS);

        refresh();
    }

    VBox pane() {
        return pane;
    }

    void refresh() {
        Long selectedOrdinal = selectedMessageOrdinal();
        boolean wasFollowing = followEnd;

        String selectedDirection = directionFilter.getValue();
        String selectedNode = nodeFilter.getValue();
        String selectedType = typeFilter.getValue();

        List<CentralSystemSimulator.MessageView> snapshot = simulator.messages();
        messages.setAll(snapshot);

        Set<String> nodes = new LinkedHashSet<>();
        nodes.add(ALL);
        Set<String> types = new LinkedHashSet<>();
        types.add(ALL);
        for (CentralSystemSimulator.MessageView message : snapshot) {
            nodes.add(message.data().timingNodeId().value());
            types.add(message.data().family().name());
        }

        nodeFilter.getItems().setAll(nodes);
        typeFilter.getItems().setAll(types);
        nodeFilter.setValue(
                selectedNode != null && nodes.contains(selectedNode) ? selectedNode : ALL);
        typeFilter.setValue(
                selectedType != null && types.contains(selectedType) ? selectedType : ALL);
        directionFilter.setValue(
                selectedDirection == null ? ALL : selectedDirection);

        rebuildRows(selectedOrdinal, wasFollowing);
    }

    private void configureTable() {
        TableColumn<MessageRow, String> direction =
                column("Dir", value -> messageValue(value, CentralMessagesPane::directionText));
        TableColumn<MessageRow, String> node =
                column(
                        "Node",
                        value -> messageValue(
                                value,
                                message -> message.data().timingNodeId().value()));
        TableColumn<MessageRow, String> type =
                column(
                        "Type",
                        value -> messageValue(
                                value,
                                message -> message.data().family().name()));
        TableColumn<MessageRow, String> position =
                column(
                        "Stream / seq",
                        value -> messageValue(
                                value,
                                message -> positionText(message.data())));
        TableColumn<MessageRow, String> time =
                column(
                        "Time",
                        value -> messageValue(
                                value,
                                message -> message.timestamp().toString()));
        TableColumn<MessageRow, String> summary =
                column(
                        "Summary",
                        value -> value.followEnd()
                                ? "— Follow end —"
                                : summaryText(value.message().data()));

        direction.setMinWidth(62);
        direction.setPrefWidth(62);
        direction.setMaxWidth(62);
        direction.setResizable(false);
        node.setPrefWidth(70);
        type.setPrefWidth(120);
        position.setPrefWidth(190);
        time.setPrefWidth(205);
        summary.setPrefWidth(420);

        table.getColumns().setAll(direction, node, type, position, time, summary);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No matching Central messages"));
        table.getStyleClass().add("engineering-traffic-table");
    }

    private void applyFilter() {
        if (rebuildingRows) {
            return;
        }
        rebuildRows(selectedMessageOrdinal(), followEnd);
    }

    private void rebuildRows(Long selectedOrdinal, boolean keepFollowing) {
        rebuildingRows = true;
        try {
            rows.setAll(visibleRows(
                    messages,
                    directionFilter.getValue(),
                    nodeFilter.getValue(),
                    typeFilter.getValue()));

            if (keepFollowing) {
                selectFollowEnd();
            } else if (selectedOrdinal != null) {
                selectMessage(selectedOrdinal.longValue());
            } else {
                table.getSelectionModel().clearSelection();
            }
        } finally {
            rebuildingRows = false;
        }
    }

    private void selectionChanged(MessageRow selected) {
        if (rebuildingRows) {
            return;
        }

        if (selected == null) {
            followEnd = false;
            rawMessage.clear();
            return;
        }

        if (selected.followEnd()) {
            followEnd = true;
            rawMessage.clear();
            table.scrollTo(rows.size() - 1);
            return;
        }

        followEnd = false;
        rawMessage.setText(ApiEventClient.encodeUpstreamData(selected.message().data()));
    }

    private void selectFollowEnd() {
        if (rows.isEmpty()) {
            return;
        }

        int index = rows.size() - 1;
        followEnd = true;
        table.getSelectionModel().select(index);
        table.scrollTo(index);
        rawMessage.clear();
    }

    private void selectMessage(long ordinal) {
        for (int index = 0; index < rows.size(); index++) {
            MessageRow row = rows.get(index);
            if (!row.followEnd() && row.message().ordinal() == ordinal) {
                table.getSelectionModel().select(index);
                table.scrollTo(index);
                return;
            }
        }
        table.getSelectionModel().clearSelection();
    }

    private Long selectedMessageOrdinal() {
        MessageRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || selected.followEnd()) {
            return null;
        }
        return Long.valueOf(selected.message().ordinal());
    }

    static List<MessageRow> visibleRows(
            List<CentralSystemSimulator.MessageView> messages,
            String direction,
            String node,
            String type) {
        List<MessageRow> result = new ArrayList<>();
        for (CentralSystemSimulator.MessageView message : messages) {
            if (matches(message, direction, node, type)) {
                result.add(MessageRow.message(message));
            }
        }
        result.add(MessageRow.followEnd());
        return List.copyOf(result);
    }

    static boolean matches(
            CentralSystemSimulator.MessageView message,
            String direction,
            String node,
            String type) {
        if (message == null) {
            return false;
        }

        String directionValue =
                direction == null ? ALL : direction.trim().toUpperCase(Locale.ROOT);
        String nodeValue = node == null ? ALL : node.trim();
        String typeValue = type == null ? ALL : type.trim();

        return (ALL.equals(directionValue)
                        || message.direction().name().equals(directionValue))
                && (ALL.equals(nodeValue)
                        || message.data().timingNodeId().value().equals(nodeValue))
                && (ALL.equals(typeValue)
                        || message.data().family().name().equals(typeValue));
    }

    static String directionText(CentralSystemSimulator.MessageView message) {
        return message.direction() == CentralSystemSimulator.MessageDirection.IN
                ? "IN ←"
                : "OUT →";
    }

    static String positionText(UpstreamData data) {
        return switch (data.family()) {
            case TIMING_DATA -> timingDataPosition((UpstreamData.TimingDataTransfer) data);
            case RACE_DATA -> "RACE_DATA #" + ((UpstreamData.RaceData) data).sequence();
            case SYNC -> {
                UpstreamData.Sync sync = (UpstreamData.Sync) data;
                yield sync.stream().name() + " head " + sync.sequence();
            }
            case ACK -> {
                UpstreamData.Ack ack = (UpstreamData.Ack) data;
                yield ack.stream().name() + " through " + ack.sequence();
            }
            case REQ -> {
                UpstreamData.Req req = (UpstreamData.Req) data;
                yield req.stream().name() + " from " + req.fromSequence();
            }
            case PING, STATUS -> "—";
        };
    }

    static String summaryText(UpstreamData data) {
        return switch (data.family()) {
            case TIMING_DATA -> {
                UpstreamData.TimingDataTransfer transfer =
                        (UpstreamData.TimingDataTransfer) data;
                yield transfer.records().size() + " TimingData record(s)";
            }
            case RACE_DATA -> raceDataSummary((UpstreamData.RaceData) data);
            case SYNC -> "advertise source head";
            case ACK -> "acknowledge contiguous receive head";
            case REQ -> "request replay from sequence";
            case PING -> {
                UpstreamData.Ping ping = (UpstreamData.Ping) data;
                StringBuilder text = new StringBuilder(ping.operation().name());
                if (ping.mode() != null) {
                    text.append(' ').append(ping.mode().name());
                }
                if (ping.pingId() != null) {
                    text.append(" · ").append(ping.pingId());
                }
                yield text.toString();
            }
            case STATUS -> "status";
        };
    }

    private static String messageValue(
            MessageRow row,
            Function<CentralSystemSimulator.MessageView, String> value) {
        return row.followEnd() ? "" : value.apply(row.message());
    }

    private static String timingDataPosition(UpstreamData.TimingDataTransfer transfer) {
        List<TimingRecord> records = transfer.records();
        long first = records.get(0).sequenceNumber();
        long last = records.get(records.size() - 1).sequenceNumber();
        return first == last
                ? "TIMING_DATA #" + first
                : "TIMING_DATA #" + first + ".." + last;
    }

    private static String raceDataSummary(UpstreamData.RaceData raceData) {
        int teamChanges = raceData.teamAssignmentChanges().size();
        int startChanges = raceData.stageStartTimeChanges().size();
        return teamChanges + " team change(s), "
                + startChanges + " stage-start change(s)";
    }

    private static <T> TableColumn<T, String> column(
            String title,
            Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(
                        cell.getValue() == null ? "" : value.apply(cell.getValue())));
        return column;
    }
}
