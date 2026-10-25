package oss.relay.timing.testclient.central;

import oss.relay.timing.timingdata.TimingDataCodec;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.defaultprofile.DefaultTimingDataCodec;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Record-oriented Central LogBook reconstructed from accepted inbound TIMING_DATA.
 *
 * <p>This pane deliberately ignores IF-06 batch boundaries. One row is one committed source
 * TimingRecord, matching the normal LogBook presentation rather than the Messages inspector.</p>
 */
final class CentralLogBookPane {
    private static final String ALL = "ALL";
    private static final TimingDataCodec CODEC = new DefaultTimingDataCodec();

    private final CentralSystemSimulator simulator;
    private final ComboBox<String> nodeFilter = new ComboBox<>();
    private final TableView<CentralTimingDataInfo> table = new TableView<>();
    private final CentralFollowLatestTable<CentralTimingDataInfo> follow =
            new CentralFollowLatestTable<>(table);
    private final TextArea rawRecord = new TextArea();
    private final VBox pane;

    CentralLogBookPane(CentralSystemSimulator simulator) {
        if (simulator == null) {
            throw new IllegalArgumentException("simulator must not be null");
        }
        this.simulator = simulator;

        nodeFilter.getItems().setAll(ALL);
        nodeFilter.setValue(ALL);
        nodeFilter.setPrefWidth(105);
        nodeFilter.setOnAction(event -> refreshRows());

        configureTable();

        rawRecord.setEditable(false);
        rawRecord.setWrapText(false);
        rawRecord.setPromptText("Select a Central LogBook record to inspect IF-05 JSON");
        rawRecord.setPrefRowCount(5);
        rawRecord.getStyleClass().add("engineering-raw-data");

        table.getSelectionModel().selectedItemProperty().addListener(
                (ignored, previous, selected) -> {
                    if (selected == null) {
                        rawRecord.clear();
                    } else {
                        rawRecord.setText(selected.rawJson());
                    }
                });

        HBox filters = new HBox(
                8,
                new Label("Node"),
                nodeFilter);
        filters.getStyleClass().add("engineering-traffic-filters");

        pane = new VBox(4, filters, table, rawRecord);
        pane.getStyleClass().add("engineering-data-pane");
        VBox.setVgrow(table, Priority.ALWAYS);

        refresh();
    }

    VBox pane() {
        return pane;
    }

    void refresh() {
        String selectedNode = nodeFilter.getValue();

        Set<String> nodes = new LinkedHashSet<>();
        nodes.add(ALL);
        for (TimingRecord record : simulator.timingRecords()) {
            nodes.add(String.valueOf(record.nodeId()));
        }

        nodeFilter.getItems().setAll(nodes);
        nodeFilter.setValue(
                selectedNode != null && nodes.contains(selectedNode)
                        ? selectedNode
                        : ALL);
        refreshRows();
    }

    private void refreshRows() {
        String selectedNode = nodeFilter.getValue();
        List<CentralTimingDataInfo> rows = new ArrayList<>();
        for (TimingRecord record : simulator.timingRecords()) {
            if (selectedNode != null
                    && !ALL.equals(selectedNode)
                    && !selectedNode.equals(String.valueOf(record.nodeId()))) {
                continue;
            }
            rows.add(toInfo(record));
        }
        follow.update(rows);
    }

    private void configureTable() {
        TableColumn<CentralTimingDataInfo, String> node =
                column("Node", CentralTimingDataInfo::timingNodeId);
        TableColumn<CentralTimingDataInfo, String> seq =
                column("Seq", value -> Long.toString(value.sequenceNumber()));
        TableColumn<CentralTimingDataInfo, String> type =
                column("Type", CentralTimingDataInfo::recordType);
        TableColumn<CentralTimingDataInfo, String> code =
                column("Code", value -> String.join("/", value.codes()));
        TableColumn<CentralTimingDataInfo, String> location =
                column("Location", value -> Integer.toString(value.locationId()));
        TableColumn<CentralTimingDataInfo, String> registration =
                column(
                        "RegistrationId",
                        value -> value.registrationId() == null
                                ? ""
                                : value.registrationId());
        TableColumn<CentralTimingDataInfo, String> effective =
                column("Effective", value -> CentralViewerTime.effective(value.effectiveTime()));
        TableColumn<CentralTimingDataInfo, String> recorded =
                column("Recorded", value -> CentralViewerTime.recorded(value.recordedAt()));
        TableColumn<CentralTimingDataInfo, Void> end =
                new TableColumn<>("");
        Label endLabel = new Label("End");
        endLabel.setTooltip(new Tooltip("Follow new Central LogBook rows at the end"));
        endLabel.setOnMouseClicked(event -> follow.followEnd());
        end.setGraphic(endLabel);

        node.setPrefWidth(65);
        seq.setPrefWidth(70);
        type.setPrefWidth(95);
        code.setPrefWidth(85);
        location.setPrefWidth(80);
        registration.setPrefWidth(130);
        effective.setPrefWidth(210);
        recorded.setPrefWidth(210);
        end.setMinWidth(44);
        end.setPrefWidth(44);
        end.setMaxWidth(44);
        end.setResizable(false);

        table.getColumns().setAll(
                node,
                seq,
                type,
                code,
                location,
                registration,
                effective,
                recorded,
                end);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No Central LogBook records"));
    }

    static CentralTimingDataInfo toInfo(TimingRecord record) {
        return CentralTimingDataInfo.from(record);
    }

    private static <T> TableColumn<T, String> column(
            String title,
            Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(
                        cell.getValue() == null
                                ? ""
                                : value.apply(cell.getValue())));
        return column;
    }
}
