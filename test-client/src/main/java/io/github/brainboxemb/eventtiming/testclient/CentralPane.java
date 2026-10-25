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
    private final CentralSystemSimulator simulator;
    private final CentralActions centralActions;
    private final Label connectionState = new Label("CENTRAL UPSTREAM NOT CONNECTED");
    private final ListView<CentralSystemSimulator.NodeView> nodes = new ListView<>();
    private final ListView<CentralSystemSimulator.LocationView> locations = new ListView<>();
    private final ComboBox<String> raceNode = new ComboBox<>();
    private final TextField raceRegistration = new TextField();
    private final TextField raceTeam = new TextField();
    private final TextField raceStageStart = new TextField();
    private final TextField replayFromSequence = new TextField("1");
    private final Button raceSet = new Button("Set team");
    private final Button raceDelete = new Button("Delete team");
    private final Button stageStartSet = new Button("Set start");
    private final Button stageStartDelete = new Button("Delete start");
    private final Button replayRequest = new Button("Request TIMING_DATA");
    private final Label downstreamAck = new Label("0");
    private final Label downstreamPending = new Label("—");
    private final Label raceFeedback = new Label("Central upstream not connected");
    private final VBox nodesPane;
    private final VBox locationsPane;
    private final VBox raceDataPane;
    private final Timeline heartbeatAgeRefresh;

    CentralPane(
            CentralSystemSimulator simulator,
            CentralActions centralActions) {
        if (simulator == null) {
            throw new IllegalArgumentException("simulator must not be null");
        }
        if (centralActions == null) {
            throw new IllegalArgumentException("centralActions must not be null");
        }
        this.simulator = simulator;
        this.centralActions = centralActions;

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
                .append(" [");
        if (node.locationId() != null) {
            int locationId = node.locationId().intValue();
            if (locationId < 10) {
                text.append('0');
            }
            text.append(locationId);
        } else if ("CLOSED".equals(node.lifecycleState())) {
            text.append("--");
        } else {
            text.append("??");
        }
        text.append("] · ")
                .append(heartbeatAgeText(node.lastHeartbeat(), now))
                .append(" · CNT ")
                .append(paddedCount(node.timingDataHead()));
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
        return "H -" + String.format(java.util.Locale.ROOT, "%3d", ageSeconds) + "s";
    }

    static String paddedCount(long count) {
        if (count < 0L) {
            throw new IllegalArgumentException("count must not be negative");
        }
        return String.format(java.util.Locale.ROOT, "%3d", count);
    }

    static String nodeDetailText(CentralSystemSimulator.NodeView node) {
        return "Node " + node.nodeId()
                + "\nLink " + (node.connected() ? "CONNECTED" : "STALE")
                + "\nHeartbeat " + instant(node.lastHeartbeat())
                + "\nTiming head " + node.timingDataHead()
                + "\nDN ack " + node.lastDnPacketAcknowledged()
                + "\nDN pending "
                + (node.outstandingDnPacketNr() == null
                        ? "—"
                        : node.outstandingDnPacketNr())
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
        raceStageStart.setPromptText("HH:mm:ss[.fraction] or date/time");
        replayFromSequence.setPrefColumnCount(8);

        raceSet.setOnAction(event -> applyTeamAssignment(false));
        raceDelete.setOnAction(event -> applyTeamAssignment(true));
        stageStartSet.setOnAction(event -> applyStageStart(false));
        stageStartDelete.setOnAction(event -> applyStageStart(true));
        replayRequest.setOnAction(event -> requestTimingData());
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
        form.add(new Label("Stage start"), 0, 3);
        form.add(raceStageStart, 1, 3);
        GridPane.setHgrow(raceNode, Priority.ALWAYS);
        GridPane.setHgrow(raceRegistration, Priority.ALWAYS);
        GridPane.setHgrow(raceTeam, Priority.ALWAYS);
        GridPane.setHgrow(raceStageStart, Priority.ALWAYS);

        HBox teamActions = new HBox(8, raceSet, raceDelete);
        HBox stageActions = new HBox(8, stageStartSet, stageStartDelete);
        HBox replay = new HBox(
                8,
                new Label("Replay from seq"),
                replayFromSequence,
                replayRequest);
        HBox protocol = new HBox(
                12,
                new Label("DN ack"), downstreamAck,
                new Label("DN pending"), downstreamPending);

        VBox pane = new VBox(
                10,
                new Label("Central RaceData"),
                form,
                teamActions,
                stageActions,
                new Label("TIMING_DATA replay"),
                replay,
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
                    ? centralActions.deleteTeamAssignment(nodeId, registrationId)
                    : centralActions.setTeamAssignment(nodeId, registrationId, teamId);
            raceFeedback.setText(
                    sequence == 0L
                            ? "RaceData state updated; waiting behind pending DN_PACKET"
                            : "DN_PACKET #" + sequence
                                    + (delete ? " DEL queued" : " SET queued"));
            refresh();
        } catch (RuntimeException ex) {
            showRaceError(ex);
        }
    }

    private void applyStageStart(boolean delete) {
        String nodeId = raceNode.getValue();
        String registrationId = raceRegistration.getText();
        String start = raceStageStart.getText();

        try {
            long packetNr = delete
                    ? centralActions.deleteStageStart(nodeId, registrationId)
                    : centralActions.setStageStart(nodeId, registrationId, start);
            raceFeedback.setText(
                    packetNr == 0L
                            ? "Stage-start change queued behind pending DN_PACKET"
                            : "DN_PACKET #" + packetNr
                                    + (delete ? " start DEL queued" : " start SET queued"));
            refresh();
        } catch (RuntimeException ex) {
            showRaceError(ex);
        }
    }

    private void requestTimingData() {
        String nodeId = raceNode.getValue();
        try {
            long fromSequence =
                    Long.parseLong(
                            replayFromSequence.getText().trim());
            long packetNr =
                    centralActions.requestTimingData(
                            nodeId,
                            fromSequence);
            raceFeedback.setText(
                    packetNr == 0L
                            ? "TIMING_DATA_REQ queued behind pending DN_PACKET"
                            : "DN_PACKET #" + packetNr
                                    + " TIMING_DATA_REQ from "
                                    + fromSequence
                                    + " queued");
            refresh();
        } catch (NumberFormatException ex) {
            raceFeedback.setText("Replay from sequence must be an integer");
        } catch (RuntimeException ex) {
            showRaceError(ex);
        }
    }

    private void showRaceError(RuntimeException ex) {
        String message = ex.getMessage();
        raceFeedback.setText(
                message == null || message.isBlank()
                        ? ex.getClass().getSimpleName()
                        : message);
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

        downstreamAck.setText(
                view == null
                        ? "0"
                        : Long.toString(view.lastDnPacketAcknowledged()));
        downstreamPending.setText(
                view == null || view.outstandingDnPacketNr() == null
                        ? "—"
                        : Long.toString(view.outstandingDnPacketNr()));

        boolean enabled = simulator.isConnected() && view != null;
        raceNode.setDisable(!simulator.isConnected() || simulator.nodes().isEmpty());
        raceRegistration.setDisable(!enabled);
        raceTeam.setDisable(!enabled);
        raceStageStart.setDisable(!enabled);
        replayFromSequence.setDisable(!enabled);
        raceSet.setDisable(!enabled);
        raceDelete.setDisable(!enabled);
        stageStartSet.setDisable(!enabled);
        stageStartDelete.setDisable(!enabled);
        replayRequest.setDisable(!enabled);
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
