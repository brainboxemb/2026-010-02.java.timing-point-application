package oss.relay.timing.testclient.central;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Central-owned Stage/StageStartTimes inspector. */
final class CentralStagesPane {
    private final CentralSystemSimulator simulator;
    private final ComboBox<Integer> stageSelector = new ComboBox<>();
    private final TableView<CentralSystemSimulator.StageStartView> startTimes =
            new TableView<>();
    private final VBox pane;

    CentralStagesPane(CentralSystemSimulator simulator) {
        if (simulator == null) {
            throw new IllegalArgumentException(
                    "simulator must not be null");
        }
        this.simulator = simulator;

        stageSelector.setPromptText("Stage");
        stageSelector.setPrefWidth(105);
        stageSelector.setOnAction(event -> refreshRows());

        configureTable();

        HBox filters = new HBox(
                8,
                new Label("Stage"),
                stageSelector);
        filters.getStyleClass().add("engineering-traffic-filters");

        pane = new VBox(4, filters, startTimes);
        pane.getStyleClass().add("engineering-data-pane");
        VBox.setVgrow(startTimes, Priority.ALWAYS);

        refresh();
    }

    javafx.scene.Node pane() {
        return pane;
    }

    void refresh() {
        Integer selected = stageSelector.getValue();
        List<CentralSystemSimulator.StageView> stages =
                simulator.stages();
        List<Integer> ids = stageIds(stages);

        stageSelector.getItems().setAll(ids);
        if (selected != null && ids.contains(selected)) {
            stageSelector.setValue(selected);
        } else if (!ids.isEmpty()) {
            stageSelector.setValue(ids.get(0));
        } else {
            stageSelector.setValue(null);
        }

        refreshRows();
    }

    private void refreshRows() {
        startTimes.setItems(
                FXCollections.observableArrayList(
                        startTimesForStage(
                                simulator.stages(),
                                stageSelector.getValue())));
    }

    private void configureTable() {
        TableColumn<CentralSystemSimulator.StageStartView, String> registration =
                column(
                        "RegistrationId",
                        CentralSystemSimulator.StageStartView::registrationId);
        TableColumn<CentralSystemSimulator.StageStartView, String> start =
                column(
                        "StartTime",
                        CentralSystemSimulator.StageStartView::start);

        registration.setPrefWidth(150);
        start.setPrefWidth(220);

        startTimes.getColumns().setAll(registration, start);
        startTimes.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        startTimes.setPlaceholder(
                new Label("No start times for selected stage"));
    }

    static List<Integer> stageIds(
            List<CentralSystemSimulator.StageView> stages) {
        if (stages == null) {
            throw new IllegalArgumentException(
                    "stages must not be null");
        }
        List<Integer> result =
                new ArrayList<>(stages.size());
        for (CentralSystemSimulator.StageView stage : stages) {
            result.add(Integer.valueOf(stage.stageId()));
        }
        return List.copyOf(result);
    }

    static List<CentralSystemSimulator.StageStartView> startTimesForStage(
            List<CentralSystemSimulator.StageView> stages,
            Integer stageId) {
        if (stages == null) {
            throw new IllegalArgumentException(
                    "stages must not be null");
        }
        if (stageId == null) {
            return List.of();
        }
        for (CentralSystemSimulator.StageView stage : stages) {
            if (stage.stageId() == stageId.intValue()) {
                return stage.startTimes();
            }
        }
        return List.of();
    }

    private static <T> TableColumn<T, String> column(
            String title,
            Function<T, String> value) {
        TableColumn<T, String> column =
                new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(
                        cell.getValue() == null
                                ? ""
                                : value.apply(cell.getValue())));
        return column;
    }
}
