package oss.relay.timing.testclient.central;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Function;

/** Central-owned RegistrationId-scoped stage-start inspector. */
final class CentralStageStartsPane {
    private final CentralSystemSimulator simulator;
    private final TableView<CentralSystemSimulator.StageStartView> startTimes =
            new TableView<>();
    private final VBox pane;

    CentralStageStartsPane(CentralSystemSimulator simulator) {
        if (simulator == null) {
            throw new IllegalArgumentException(
                    "simulator must not be null");
        }
        this.simulator = simulator;

        configureTable();

        pane = new VBox(4, startTimes);
        pane.getStyleClass().add("engineering-data-pane");
        VBox.setVgrow(startTimes, Priority.ALWAYS);

        refresh();
    }

    javafx.scene.Node pane() {
        return pane;
    }

    void refresh() {
        startTimes.setItems(
                FXCollections.observableArrayList(
                        simulator.stageStarts()));
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
                new Label("No stage start times"));
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
