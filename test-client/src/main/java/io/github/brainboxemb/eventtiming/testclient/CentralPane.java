package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;

/** JavaFX projection of the Central System Simulator state. */
final class CentralPane {
    private final CentralSystemSimulator simulator;
    private final Label connectionState = new Label("NOT CONNECTED");
    private final TableView<CentralSystemSimulator.SystemView> systems = new TableView<>();
    private final TableView<CentralSystemSimulator.LocationView> locations = new TableView<>();
    private final VBox systemsPane;
    private final VBox locationsPane;

    CentralPane(CentralSystemSimulator simulator) {
        if (simulator == null) {
            throw new IllegalArgumentException("simulator must not be null");
        }
        this.simulator = simulator;

        configureSystems();
        configureLocations();

        connectionState.getStyleClass().add("sync-state");
        systemsPane = new VBox(4, connectionState, systems);
        locationsPane = new VBox(locations);
        VBox.setVgrow(systems, Priority.ALWAYS);
        VBox.setVgrow(locations, Priority.ALWAYS);
        refresh();
    }

    javafx.scene.Node systemsPane() {
        return systemsPane;
    }

    javafx.scene.Node locationsPane() {
        return locationsPane;
    }

    void refresh() {
        connectionState.setText(
                simulator.isConnected()
                        ? "CENTRAL UPSTREAM CONNECTED"
                        : "CENTRAL UPSTREAM NOT CONNECTED");
        systems.setItems(FXCollections.observableArrayList(simulator.systems()));
        locations.setItems(FXCollections.observableArrayList(simulator.locations()));
    }

    private void configureSystems() {
        TableColumn<CentralSystemSimulator.SystemView, String> node =
                textColumn("Node", CentralSystemSimulator.SystemView::nodeId);
        TableColumn<CentralSystemSimulator.SystemView, String> link =
                textColumn("Link", value -> value.connected() ? "CONNECTED" : "STALE");
        TableColumn<CentralSystemSimulator.SystemView, String> heartbeat =
                textColumn("Heartbeat", value -> instant(value.lastHeartbeat()));
        TableColumn<CentralSystemSimulator.SystemView, String> head =
                textColumn("Timing head", value -> Long.toString(value.timingDataHead()));
        TableColumn<CentralSystemSimulator.SystemView, String> raceHead =
                textColumn("Race head", value -> Long.toString(value.raceDataHead()));
        TableColumn<CentralSystemSimulator.SystemView, String> raceAck =
                textColumn("Race ack", value -> Long.toString(value.raceDataAcknowledged()));
        TableColumn<CentralSystemSimulator.SystemView, String> location =
                textColumn("Location", value ->
                        value.locationId() == null ? "—" : value.locationId().toString());
        TableColumn<CentralSystemSimulator.SystemView, String> last =
                textColumn("Last LogBook", CentralPane::lastRecord);

        systems.getColumns().setAll(
                node, link, heartbeat, head, raceHead, raceAck, location, last);
        systems.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        systems.setPlaceholder(new Label("No upstream systems observed"));
    }

    private void configureLocations() {
        TableColumn<CentralSystemSimulator.LocationView, String> location =
                textColumn("Location", value -> Integer.toString(value.locationId()));
        TableColumn<CentralSystemSimulator.LocationView, String> state =
                textColumn("State", CentralSystemSimulator.LocationView::state);
        TableColumn<CentralSystemSimulator.LocationView, String> node =
                textColumn("Node", value -> value.nodeId() == null ? "—" : value.nodeId());
        TableColumn<CentralSystemSimulator.LocationView, String> sequence =
                textColumn("Last seq", value ->
                        value.sequence() == 0L ? "—" : Long.toString(value.sequence()));
        TableColumn<CentralSystemSimulator.LocationView, String> changed =
                textColumn("Changed", value -> instant(value.changedAt()));

        locations.getColumns().setAll(location, state, node, sequence, changed);
        locations.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        locations.setPlaceholder(new Label("No configured or observed locations"));
    }

    private static <T> TableColumn<T, String> textColumn(
            String title,
            java.util.function.Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return column;
    }

    private static String lastRecord(CentralSystemSimulator.SystemView value) {
        TimingRecord record = value.lastRecord();
        if (record == null) {
            return "—";
        }
        return "#" + record.sequenceNumber()
                + " " + recordType(record)
                + " loc " + record.locationId()
                + " " + recordCode(record);
    }

    private static String recordType(TimingRecord record) {
        return switch (record.kind()) {
            case AUTOMATIC_REGISTRATION -> "AUTO_REG";
            case MANUAL_REGISTRATION -> "MAN_REG";
            case NODE_OPEN, NODE_CLOSE -> "NODE_INFO";
        };
    }

    private static String recordCode(TimingRecord record) {
        return switch (record.kind()) {
            case AUTOMATIC_REGISTRATION, MANUAL_REGISTRATION ->
                    record.action() == null ? "—" : record.action().name();
            case NODE_OPEN -> "OPEN";
            case NODE_CLOSE -> "CLOSE";
        };
    }

    private static String instant(Instant value) {
        return value == null ? "—" : value.toString();
    }
}
