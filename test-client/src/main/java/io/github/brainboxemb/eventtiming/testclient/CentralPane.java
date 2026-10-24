package io.github.brainboxemb.eventtiming.testclient;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.Instant;
import java.util.List;

/** JavaFX projections and operator controls for the Central System Simulator state. */
final class CentralPane implements AutoCloseable {
    interface RaceDataActions {
        long setTeamAssignment(String nodeId, String registrationId, String teamId);

        long deleteTeamAssignment(String nodeId, String registrationId);
    }

    private final CentralSystemSimulator simulator;
    private final RaceDataActions raceDataActions;
    private final Label connectionState = new Label("CENTRAL UPSTREAM NOT CONNECTED");
    private final ListView<CentralSystemSimulator.NodeView> nodes = new ListView<>();
    private final ListView<CentralSystemSimulator.LocationView> locations = new ListView<>();
    private final ComboBox<String> raceNode = new ComboBox<>();
    private final TextField raceRegistration = new TextField();
    private final TextField raceTeam = new TextField();
    private final Button raceSet = new Button("Set team");
    private final Button raceDelete = new Button("Delete team");
    private final Label raceHead = new Label("0");
    private final Label raceAck = new Label("0");
    private final Label raceFeedback = new Label("Central upstream not connected");
    private final VBox nodesPane;
    private final VBox locationsPane;
    private final VBox raceDataPane;
    private final Timeline heartbeatAgeRefresh;

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

        configureNodes();
        configureLocations();
        configureRaceData();

        connectionState.getStyleClass().add("sync-state");
        nodesPane = new VBox(6, connectionState, nodes);
        nodesPane.getStyleClass().add("engineering-explorer-pane");
        locationsPane = new VBox(locations);
        locationsPane.getStyleClass().add("engineering-explorer-pane");
        raceDataPane = createRaceDataPane();
        VBox.setVgrow(nodes, Priority.ALWAYS);
        VBox.setVgrow(locations, Priority.ALWAYS);

        heartbeatAgeRefresh = new Timeline(
                new KeyFrame(Duration.seconds(10), event -> nodes.refresh()));
        heartbeatAgeRefresh.setCycleCount(Timeline.INDEFINITE);
        heartbeatAgeRefresh.play();

        refresh();
    }

    javafx.scene.Node nodesPane() {
        return nodesPane;
    }

    javafx.scene.Node locationsPane() {
        return locationsPane;
    }

    javafx.scene.Node raceDataPane() {
        return raceDataPane;
    }

    void refresh() {
        boolean connected = simulator.isConnected();
        connectionState.setText(
                connected
                        ? "CENTRAL UPSTREAM CONNECTED"
                        : "CENTRAL UPSTREAM NOT CONNECTED");
        connectionState.setVisible(!connected);
        connectionState.setManaged(!connected);

        List<CentralSystemSimulator.NodeView> nodeViews = simulator.nodes();
        nodes.setItems(FXCollections.observableArrayList(nodeViews));
        locations.setItems(FXCollections.observableArrayList(simulator.locations()));
        refreshRaceNodes(nodeViews);
        refreshRaceDataState();
    }

    private void configureNodes() {
        nodes.setPlaceholder(new Label("No Central nodes observed"));
        nodes.setCellFactory(view -> new ListCell<>() {
            private final Tooltip detail = new Tooltip();

            {
                setOnMouseEntered(event -> refreshTooltip());
            }

            @Override
            protected void updateItem(
                    CentralSystemSimulator.NodeView value,
                    boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : compactNodeText(value, Instant.now()));
                refreshTooltip();
            }

            private void refreshTooltip() {
                CentralSystemSimulator.NodeView value = getItem();
                if (isEmpty() || value == null) {
                    setTooltip(null);
                    return;
                }
                detail.setText(nodeDetailText(value));
                setTooltip(detail);
            }
        });
    }

    private void configureLocations() {
        locations.setPlaceholder(new Label("No configured or observed locations"));
        locations.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(
                    CentralSystemSimulator.LocationView value,
                    boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : compactLocationText(value));
            }
        });
    }

    static String compactNodeText(
            CentralSystemSimulator.NodeView node,
            Instant now) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }

        StringBuilder text = new StringBuilder(node.nodeId())
                .append(" · ")
                .append(node.lifecycleState());
        if (node.locationId() != null) {
            text.append(" · Location ").append(node.locationId());
        }
        text.append(" · ").append(heartbeatAgeText(node.lastHeartbeat(), now));
        return text.toString();
    }

    static String heartbeatAgeText(Instant lastHeartbeat, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        if (lastHeartbeat == null) {
            return "H ---";
        }

        long ageSeconds = java.time.Duration.between(lastHeartbeat, now).getSeconds();
        ageSeconds = Math.max(0L, Math.min(999L, ageSeconds));
        return "H -" + ageSeconds + "s";
    }

    static String nodeDetailText(CentralSystemSimulator.NodeView node) {
        return "Node " + node.nodeId()
                + "\nLink " + (node.connected() ? "CONNECTED" : "STALE")
                + "\nHeartbeat " + instant(node.lastHeartbeat())
                + "\nTiming head " + node.timingDataHead()
                + "\nRace head " + node.raceDataHead()
                + "\nRace ACK " + node.raceDataAcknowledged()
                + "\nLocation "
                + (node.locationId() == null ? "—" : node.locationId())
                + "\nLast LogBook " + lastRecord(node);
    }

    static String compactLocationText(CentralSystemSimulator.LocationView location) {
        StringBuilder text = new StringBuilder()
                .append(location.locationId())
                .append(" · ")
                .append(location.state());
        if (location.nodeId() != null) {
            text.append(" · ").append(location.nodeId());
        }
        return text.toString();
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

    private void refreshRaceNodes(List<CentralSystemSimulator.NodeView> nodeViews) {
        String selected = raceNode.getValue();
        List<String> nodeIds = nodeViews.stream()
                .map(CentralSystemSimulator.NodeView::nodeId)
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
        CentralSystemSimulator.NodeView view = simulator.nodes().stream()
                .filter(value -> value.nodeId().equals(selected))
                .findFirst()
                .orElse(null);

        raceHead.setText(view == null ? "0" : Long.toString(view.raceDataHead()));
        raceAck.setText(view == null ? "0" : Long.toString(view.raceDataAcknowledged()));

        boolean enabled = simulator.isConnected() && view != null;
        raceNode.setDisable(!simulator.isConnected() || simulator.nodes().isEmpty());
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

    private static String lastRecord(CentralSystemSimulator.NodeView value) {
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

    @Override
    public void close() {
        heartbeatAgeRefresh.stop();
    }
}
