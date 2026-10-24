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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Wide semantic IF-06 message inspector for the Central System Simulator. */
final class CentralMessagesPane {
    private static final String ALL = "ALL";
    private static final String TIME_ONLY = "Time";
    private static final String DATE_AND_TIME = "Date + time";
    private static final DateTimeFormatter TIME_FORMAT =
            new DateTimeFormatterBuilder()
                    .appendPattern("HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
                    .toFormatter(Locale.ROOT)
                    .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_TIME_FORMAT =
            new DateTimeFormatterBuilder()
                    .appendPattern("uuuu-MM-dd HH:mm:ss")
                    .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
                    .toFormatter(Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

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

        static MessageRow endRow() {
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
    private final ComboBox<String> timeView = new ComboBox<>();
    private final Button endButton = new Button("End");
    private final TableView<MessageRow> table = new TableView<>(rows);
    private TableColumn<MessageRow, String> messageTimeColumn;
    private TableColumn<MessageRow, String> receivedTimeColumn;
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

        timeView.getItems().setAll(TIME_ONLY, DATE_AND_TIME);
        timeView.setValue(TIME_ONLY);
        timeView.setPrefWidth(125);

        directionFilter.setOnAction(event -> applyFilter());
        nodeFilter.setOnAction(event -> applyFilter());
        typeFilter.setOnAction(event -> applyFilter());
        timeView.setOnAction(event -> applyTimeView());

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
                new Label("Time view"),
                timeView,
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

        rebuildingRows = true;
        try {
            nodeFilter.getItems().setAll(nodes);
            typeFilter.getItems().setAll(types);
            nodeFilter.setValue(
                    selectedNode != null && nodes.contains(selectedNode) ? selectedNode : ALL);
            typeFilter.setValue(
                    selectedType != null && types.contains(selectedType) ? selectedType : ALL);
            directionFilter.setValue(
                    selectedDirection == null ? ALL : selectedDirection);
        } finally {
            rebuildingRows = false;
        }

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
                        "Packet / position",
                        value -> messageValue(
                                value,
                                message -> positionText(message.data())));
        messageTimeColumn =
                column(
                        "Time",
                        value -> messageValue(
                                value,
                                message -> formatTime(
                                        message.messageTime(),
                                        showDate())));
        receivedTimeColumn =
                column(
                        "Received",
                        value -> messageValue(
                                value,
                                message -> formatTime(
                                        message.receivedAt(),
                                        showDate())));
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
        summary.setPrefWidth(420);
        applyTimeColumnWidths();

        table.getColumns().setAll(
                direction,
                node,
                type,
                position,
                messageTimeColumn,
                receivedTimeColumn,
                summary);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No matching Central messages"));
        table.getStyleClass().add("engineering-traffic-table");
    }

    /** Refreshes table formatting and widths after the time-view choice changes. */
    private void applyTimeView() {
        applyTimeColumnWidths();
        table.refresh();
    }

    /** Keeps the default time-only table compact while allowing the date view to expand. */
    private void applyTimeColumnWidths() {
        double width =
                showDate()
                        ? 190.0
                        : 115.0;
        if (messageTimeColumn != null) {
            messageTimeColumn.setPrefWidth(width);
        }
        if (receivedTimeColumn != null) {
            receivedTimeColumn.setPrefWidth(width);
        }
    }

    /** Returns whether the selected presentation includes the UTC calendar date. */
    private boolean showDate() {
        return DATE_AND_TIME.equals(timeView.getValue());
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
            updateVisibleRows(visibleRows(
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

    /**
     * Applies incremental changes to keep JavaFX's virtualized viewport stable while
     * a historical message is being inspected. A journal rollover removes old rows.
     */
    private void updateVisibleRows(List<MessageRow> next) {
        int common = 0;
        int existingMessages = Math.max(0, rows.size() - 1);
        int nextMessages = Math.max(0, next.size() - 1);
        while (common < Math.min(existingMessages, nextMessages)
                && rows.get(common).equals(next.get(common))) {
            common++;
        }
        if (common == existingMessages && nextMessages >= existingMessages) {
            rows.addAll(existingMessages, next.subList(common, nextMessages));
        } else if (!rows.equals(next)) {
            // Filters and bounded history rollover are not plain tail appends.
            rows.setAll(next);
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
        rawMessage.setText(
                detailPayload(
                        selected.message().data(),
                        selected.message().rawPayloadJson()));
    }

    /**
     * Formats only presentation whitespace for a multi-record TIMING_DATA payload.
     *
     * <p>The retained wire payload remains unchanged. Each top-level record in the IF-06
     * data array stays compact and is placed on its own line for inspection.</p>
     */
    static String detailPayload(
            UpstreamData data,
            String rawPayloadJson) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (rawPayloadJson == null) {
            throw new IllegalArgumentException("rawPayloadJson must not be null");
        }
        if (data.family() != UpstreamData.Family.TIMING_DATA
                || ((UpstreamData.TimingDataTransfer) data.message())
                        .records().size() <= 1) {
            return rawPayloadJson;
        }
        return formatTopLevelDataArray(rawPayloadJson);
    }

    /**
     * Adds line breaks around top-level data-array items without parsing or re-encoding JSON.
     */
    private static String formatTopLevelDataArray(String rawJson) {
        String marker = "\"data\":[";
        int markerIndex = rawJson.indexOf(marker);
        if (markerIndex < 0) {
            return rawJson;
        }

        int arrayStart = markerIndex + marker.length();
        StringBuilder formatted =
                new StringBuilder(rawJson.length() + 32);
        formatted.append(rawJson, 0, arrayStart);
        formatted.append('\n').append("  ");

        boolean inString = false;
        boolean escaped = false;
        int nestedDepth = 0;

        for (int index = arrayStart; index < rawJson.length(); index++) {
            char ch = rawJson.charAt(index);

            if (inString) {
                formatted.append(ch);
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }

            if (ch == '"') {
                inString = true;
                formatted.append(ch);
                continue;
            }

            if (ch == '{' || ch == '[') {
                nestedDepth++;
                formatted.append(ch);
                continue;
            }
            if (ch == '}') {
                nestedDepth--;
                formatted.append(ch);
                continue;
            }
            if (ch == ']') {
                if (nestedDepth == 0) {
                    formatted.append('\n');
                    formatted.append(rawJson.substring(index));
                    return formatted.toString();
                }
                nestedDepth--;
                formatted.append(ch);
                continue;
            }
            if (ch == ',' && nestedDepth == 0) {
                formatted.append(',').append('\n').append("  ");
                continue;
            }

            formatted.append(ch);
        }

        return rawJson;
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
                return;
            }
        }
        table.getSelectionModel().clearSelection();
        rawMessage.clear();
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
        result.add(MessageRow.endRow());
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

    /**
     * Formats Central message times in UTC without exposing sub-millisecond noise.
     *
     * <p>The underlying Instant is retained unchanged; only the table presentation is compacted.</p>
     */
    static String formatTime(
            Instant instant,
            boolean includeDate) {
        if (instant == null) {
            return "—";
        }
        return (includeDate ? DATE_TIME_FORMAT : TIME_FORMAT)
                .format(instant);
    }

    static String directionText(CentralSystemSimulator.MessageView message) {
        return message.direction() == CentralSystemSimulator.MessageDirection.IN
                ? "IN ←"
                : "OUT →";
    }

    static String positionText(UpstreamData data) {
        String packet =
                data.packetNr() == 0L
                        ? "P0"
                        : "P" + data.packetNr();

        return switch (data.family()) {
            case TIMING_DATA -> packet + " · "
                    + timingDataPosition(
                            (UpstreamData.TimingDataTransfer) data.message());
            case RACE_DATA -> {
                UpstreamData.RaceData raceData =
                        (UpstreamData.RaceData) data.message();
                yield packet + " · RACE_DATA L"
                        + raceData.locationId().value();
            }
            case TIMING_DATA_REQ -> packet + " · from "
                    + ((UpstreamData.TimingDataRequest) data.message())
                            .fromSequence();
            case SEQ -> {
                UpstreamData.Sequence sequence =
                        (UpstreamData.Sequence) data.message();
                yield packet + " · " + sequence.operation().name()
                        + (sequence.sequence() == null
                                ? ""
                                : " " + sequence.sequence());
            }
            case RECV -> packet + " · DN through "
                    + ((UpstreamData.Receive) data.message())
                            .receivedPacketNr();
            case PING, STATUS -> packet;
        };
    }

    static String summaryText(UpstreamData data) {
        return switch (data.family()) {
            case TIMING_DATA -> {
                UpstreamData.TimingDataTransfer transfer =
                        (UpstreamData.TimingDataTransfer) data.message();
                yield transfer.records().size() + " TimingData record(s)";
            }
            case RACE_DATA ->
                    raceDataSummary(
                            (UpstreamData.RaceData) data.message());
            case TIMING_DATA_REQ -> "request retained TimingData";
            case SEQ -> {
                UpstreamData.Sequence sequence =
                        (UpstreamData.Sequence) data.message();
                yield "TimingData position " + sequence.operation().name();
            }
            case RECV -> "report last processed DN_PACKET";
            case PING -> {
                UpstreamData.Ping ping =
                        (UpstreamData.Ping) data.message();
                StringBuilder text =
                        new StringBuilder(ping.operation().name());
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
        TimingRecord firstRecord = records.get(0);
        TimingRecord lastRecord = records.get(records.size() - 1);
        long first = firstRecord.sequenceNumber();
        long last = lastRecord.sequenceNumber();

        if (first == last) {
            return timingRecordType(firstRecord.kind()) + " #" + first;
        }
        return "#" + first + ".." + last;
    }

    /** Returns the compact IF-05 record type already used by operator-facing LogBook output. */
    private static String timingRecordType(TimingRecord.Kind kind) {
        return switch (kind) {
            case AUTOMATIC_REGISTRATION -> "AUTO_REG";
            case MANUAL_REGISTRATION -> "MAN_REG";
            case NODE_OPEN -> "NODE_OPEN";
            case NODE_CLOSE -> "NODE_CLOSE";
        };
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
