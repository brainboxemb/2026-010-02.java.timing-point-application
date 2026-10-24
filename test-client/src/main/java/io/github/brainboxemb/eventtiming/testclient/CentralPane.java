package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.List;

/** JavaFX projection and operator controls for the Central System Simulator state. */
final class CentralPane {
    interface RaceDataActions {
        long setTeamAssignment(String nodeId, String registrationId, String teamId);

        long deleteTeamAssignment(String nodeId, String registrationId);
    }

    private final CentralSystemSimulator simulator;
    private final RaceDataActions raceDataActions;
    private final Label connectionState = new Label("NOT CONNECTED");
    private final TableView<CentralSystemSimulator.SystemView> systems = new TableView<>();
    private final TableView<CentralSystemSimulator.LocationView> locations = new TableView<>();
    private final ComboBox<String> raceNode = new ComboBox<>();
    private final TextField raceRegistration = new TextField();
    private final TextField raceTeam = new TextField();
    private final Button raceSet = new Button("Set team");
    private final Button raceDelete = new Button("Delete team");
    private final Label raceHead = new Label("0");
    private final Label raceAck = new Label("0");
    private final Label raceFeedback = new Label("Central upstream not connected");
    private final VBox systemsPane;
    private final VBox locationsPane;
    private final VBox raceDataPane;

    CentralPane(
            CentralSystemSimulator simulator,
            RaceDataActions raceDataActions) {
        if (simulator == null) {
            throw new IllegalArgumentException("simulator must not be null");
        }
        if (raceDataActions == null) {
            throw new IllegalArgumentException("raceDataActions must not be null");
        }
        this.simulator = simulator;
        this.raceDataActions = raceDataActions;

        configureSystems();
        configureLocations();
        configureRaceData();

        connectionState.getStyleClass().add("sync-state");
        systemsPane = new VBox(4, connectionState, systems);
        locationsPane = new VBox(locations);
        raceDataPane = createRaceDataPane();
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

    javafx.scene.Node raceDataPane() {
        return raceDataPane;
    }

    void refresh() {
        connectionState.setText(
                simulator.isConnected()
                        ? "CENTRAL UPSTREAM CONNECTED"
                        : "CENTRAL UPSTREAM NOT CONNECTED");
        List<CentralSystemSimulator.SystemView> systemViews = simulator.systems();
        systems.setItems(FXCollections.observableArrayList(systemViews));
        locations.setItems(FXCollections.observableArrayList(simulator.locations()));
        refreshRaceNodes(systemViews);
        refreshRaceDataState();
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
        TableColumn<CentralSystemSimulator.SystemView, String> raceHeadColumn =
                textColumn("Race head", value -> Long.toString(value.raceDataHead()));
        TableColumn<CentralSystemSimulator.SystemView, String> raceAckColumn =
                textColumn("Race ack", value -> Long.toString(value.raceDataAcknowledged()));
        TableColumn<CentralSystemSimulator.SystemView, String> location =
                textColumn("Location", value ->
                        value.locationId() == null ? "—" : value.locationId().toString());
        TableColumn<CentralSystemSimulator.SystemView, String> last =
                textColumn("Last LogBook", CentralPane::lastRecord);

        systems.getColumns().setAll(
                node, link, heartbeat, head, raceHeadColumn, raceAckColumn, location, last);
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

    private void configureRaceData() {
        raceNode.setPromptText("Node");
        raceNode.setMaxWidth(Double.MAX_VALUE);
        raceNode.valueProperty().addListener((ignored, oldValue, newValue) ->
                refreshRaceDataState());

        raceRegistration.setPromptText("A0001");
        raceTeam.setPromptText("TeamId");

        raceSet.setOnAction(event -> applyTeamAssignment(false));
        raceDelete.setOnAction(event -> applyTeamAssignment(true));
    }

    private VBox createRaceDataPane() {
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.add(new Label("Node"), 0, 0);
        form.add(raceNode, 1, 0);
        form.add(new Label("RegistrationId"), 0, 1);
        form.add(raceRegistration, 1, 1);
        form.add(new Label("TeamId"), 0, 2);
        form.add(raceTeam, 1, 2);
        GridPane.setHgrow(raceNode, Priority.ALWAYS);
        GridPane.setHgrow(raceRegistration, Priority.ALWAYS);
        GridPane.setHgrow(raceTeam, Priority.ALWAYS);

        HBox actions = new HBox(8, raceSet, raceDelete);
        HBox protocol = new HBox(
                12,
                new Label("Race head"), raceHead,
                new Label("ACK"), raceAck);

        VBox pane = new VBox(
                10,
                new Label("Central team assignment"),
                form,
                actions,
                protocol,
                raceFeedback);
        pane.setPadding(new Insets(8));
        return pane;
    }

    private void applyTeamAssignment(boolean delete) {
        String nodeId = raceNode.getValue();
        String registrationId = raceRegistration.getText();
        String teamId = raceTeam.getText();

        try {
            long sequence = delete
                    ? raceDataActions.deleteTeamAssignment(nodeId, registrationId)
                    : raceDataActions.setTeamAssignment(nodeId, registrationId, teamId);
            raceFeedback.setText(
                    "RACE_DATA #" + sequence + (delete ? " DEL queued" : " SET queued"));
            refresh();
        } catch (RuntimeException ex) {
            String message = ex.getMessage();
            raceFeedback.setText(
                    message == null || message.isBlank()
                            ? ex.getClass().getSimpleName()
                            : message);
        }
    }

    private void refreshRaceNodes(List<CentralSystemSimulator.SystemView> systemViews) {
        String selected = raceNode.getValue();
        List<String> nodeIds = systemViews.stream()
                .map(CentralSystemSimulator.SystemView::nodeId)
                .toList();
        raceNode.setItems(FXCollections.observableArrayList(nodeIds));

        if (selected != null && nodeIds.contains(selected)) {
            raceNode.setValue(selected);
        } else if (!nodeIds.isEmpty()) {
            raceNode.setValue(nodeIds.get(0));
        } else {
            raceNode.setValue(null);
        }
    }

    private void refreshRaceDataState() {
        String selected = raceNode.getValue();
        CentralSystemSimulator.SystemView view = simulator.systems().stream()
                .filter(value -> value.nodeId().equals(selected))
                .findFirst()
                .orElse(null);

        raceHead.setText(view == null ? "0" : Long.toString(view.raceDataHead()));
        raceAck.setText(view == null ? "0" : Long.toString(view.raceDataAcknowledged()));

        boolean enabled = simulator.isConnected() && view != null;
        raceNode.setDisable(!simulator.isConnected() || simulator.systems().isEmpty());
        raceRegistration.setDisable(!enabled);
        raceTeam.setDisable(!enabled);
        raceSet.setDisable(!enabled);
        raceDelete.setDisable(!enabled);
        if (!simulator.isConnected()) {
            raceFeedback.setText("Central upstream not connected");
        } else if (view == null) {
            raceFeedback.setText("No Central NodeId observed yet");
        }
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
