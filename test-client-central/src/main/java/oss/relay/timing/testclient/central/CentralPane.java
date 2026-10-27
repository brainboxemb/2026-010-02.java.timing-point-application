package oss.relay.timing.testclient.central;

import oss.relay.timing.timingdata.TimingRecord;
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
    private final CentralControlActions centralActions;
    private final Label connectionState = new Label("CENTRAL UPSTREAM NOT CONNECTED");
    private final ListView<CentralSystemSimulator.NodeView> nodes = new ListView<>();
    private final ListView<CentralSystemSimulator.LocationView> locations = new ListView<>();
    private final ComboBox<String> raceTeamPrefix = new ComboBox<>();
    private final TextField raceRegistration = new TextField();
    private final TextField raceTeam = new TextField();
    private final TextField raceStage = new TextField("1");
    private final ComboBox<String> raceStagePrefix = new ComboBox<>();
    private final TextField raceRegistrationFrom = new TextField();
    private final TextField raceRegistrationTo = new TextField();
    private final TextField raceStageStart = new TextField();
    private final TextField raceStageIntervalSeconds = new TextField("0");
    private final Button raceSet = new Button("Set team");
    private final Button raceDelete = new Button("Delete team");
    private final Button stageStartSet = new Button("Set start");
    private final Button stageStartDelete = new Button("Delete start");
    private final Label raceFeedback = new Label("Central state ready");

    private final ComboBox<String> timingNode = new ComboBox<>();
    private final Label timingHead = new Label("0");
    private final Label timingSourceHead = new Label("—");
    private final Button timingSourceHeadQuery = new Button("Query SI-01");
    private final TextField timingFromSequence = new TextField("1");
    private final Button timingRequest = new Button("Request TimingData");
    private final Label timingDnAck = new Label("0");
    private final Label timingDnPending = new Label("—");
    private final Label timingFeedback = new Label("Central upstream not connected");

    private final VBox nodesPane;
    private final VBox locationsPane;
    private final VBox raceDataPane;
    private final VBox timingDataPane;
    private final Timeline heartbeatAgeRefresh;

    CentralPane(
            CentralSystemSimulator simulator,
            CentralControlActions centralActions) {
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
        configureTimingData();

        connectionState.getStyleClass().add("sync-state");
        nodesPane = new VBox(6, connectionState, nodes);
        nodesPane.getStyleClass().add("engineering-explorer-pane");
        locationsPane = new VBox(locations);
        locationsPane.getStyleClass().add("engineering-explorer-pane");
        raceDataPane = createRaceDataPane();
        timingDataPane = createTimingDataPane();
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

    javafx.scene.Node timingDataPane() {
        return timingDataPane;
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
        refreshNodeSelectors(nodeViews);
        refreshRaceDataState();
        refreshTimingDataState();
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
        List<String> prefixes =
                java.util.stream.IntStream
                        .rangeClosed('A', 'Z')
                        .mapToObj(value -> Character.toString((char) value))
                        .toList();
        raceTeamPrefix.getItems().setAll(prefixes);
        raceTeamPrefix.setValue("A");
        raceTeamPrefix.setPrefWidth(72);
        raceStagePrefix.getItems().setAll(prefixes);
        raceStagePrefix.setValue("A");
        raceStagePrefix.setPrefWidth(72);

        raceRegistration.setPromptText("1");
        raceRegistrationFrom.setPromptText("1");
        raceRegistrationTo.setPromptText("100");
        raceTeam.setPromptText("TeamId");
        raceStage.setPromptText("StageId");
        raceStageStart.setPromptText("190000 or 19:00:00");
        raceStageIntervalSeconds.setPromptText("0");

        raceSet.setOnAction(event -> applyTeamAssignment(false));
        raceDelete.setOnAction(event -> applyTeamAssignment(true));
        stageStartSet.setOnAction(event -> applyStageStart(false));
        stageStartDelete.setOnAction(event -> applyStageStart(true));
    }

    private void configureTimingData() {
        timingNode.setPromptText("Node");
        timingNode.setMaxWidth(Double.MAX_VALUE);
        timingNode.valueProperty().addListener((ignored, oldValue, newValue) ->
                refreshTimingDataState());

        timingSourceHeadQuery.setTooltip(
                new Tooltip(
                        "Ask SI-01 for its current LogBook/TIMING_DATA source position via IF-06 SEQ REQ"));
        timingSourceHeadQuery.setOnAction(event -> querySourceHead());
        timingFromSequence.setPrefColumnCount(8);
        timingRequest.setOnAction(event -> requestTimingData());
    }

    private VBox createRaceDataPane() {
        GridPane teamForm = new GridPane();
        teamForm.setHgap(8);
        teamForm.setVgap(8);
        teamForm.add(new Label("Registration prefix"), 0, 0);
        teamForm.add(raceTeamPrefix, 1, 0);
        teamForm.add(new Label("Registration number"), 0, 1);
        teamForm.add(raceRegistration, 1, 1);
        teamForm.add(new Label("TeamId"), 0, 2);
        teamForm.add(raceTeam, 1, 2);
        GridPane.setHgrow(raceRegistration, Priority.ALWAYS);
        GridPane.setHgrow(raceTeam, Priority.ALWAYS);

        GridPane stageForm = new GridPane();
        stageForm.setHgap(8);
        stageForm.setVgap(8);
        stageForm.add(new Label("StageId"), 0, 0);
        stageForm.add(raceStage, 1, 0);
        stageForm.add(new Label("Registration prefix"), 0, 1);
        stageForm.add(raceStagePrefix, 1, 1);
        stageForm.add(new Label("Registration from"), 0, 2);
        stageForm.add(raceRegistrationFrom, 1, 2);
        stageForm.add(new Label("Registration to"), 0, 3);
        stageForm.add(raceRegistrationTo, 1, 3);
        stageForm.add(new Label("Start time"), 0, 4);
        stageForm.add(raceStageStart, 1, 4);
        stageForm.add(new Label("Interval seconds"), 0, 5);
        stageForm.add(raceStageIntervalSeconds, 1, 5);
        GridPane.setHgrow(raceStage, Priority.ALWAYS);
        GridPane.setHgrow(raceRegistrationFrom, Priority.ALWAYS);
        GridPane.setHgrow(raceRegistrationTo, Priority.ALWAYS);
        GridPane.setHgrow(raceStageStart, Priority.ALWAYS);
        GridPane.setHgrow(raceStageIntervalSeconds, Priority.ALWAYS);

        HBox teamActions = new HBox(8, raceSet, raceDelete);
        HBox stageActions = new HBox(8, stageStartSet, stageStartDelete);
        VBox pane = new VBox(
                10,
                new Label("Central RaceData"),
                new Label("Team Mapping"),
                teamForm,
                teamActions,
                new Label("Stage Start Times"),
                stageForm,
                stageActions,
                raceFeedback);
        pane.setPadding(new Insets(8));
        return pane;
    }

    private VBox createTimingDataPane() {
        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.add(new Label("Node"), 0, 0);
        form.add(timingNode, 1, 0);
        form.add(new Label("Received through seq"), 0, 1);
        form.add(timingHead, 1, 1);
        form.add(new Label("SI-01 LogBook count"), 0, 2);
        form.add(
                new HBox(
                        8,
                        timingSourceHead,
                        timingSourceHeadQuery),
                1,
                2);
        form.add(new Label("Request from sequence"), 0, 3);
        form.add(timingFromSequence, 1, 3);
        GridPane.setHgrow(timingNode, Priority.ALWAYS);
        GridPane.setHgrow(timingFromSequence, Priority.ALWAYS);

        HBox request = new HBox(8, timingRequest);
        HBox protocol = new HBox(
                12,
                new Label("DN ack"), timingDnAck,
                new Label("DN pending"), timingDnPending);

        VBox pane = new VBox(
                10,
                new Label("Central TimingData"),
                form,
                request,
                protocol,
                timingFeedback);
        pane.setPadding(new Insets(8));
        return pane;
    }

    private void applyTeamAssignment(boolean delete) {
        String prefix = raceTeamPrefix.getValue();
        String registrationNumber = raceRegistration.getText();
        String teamId = raceTeam.getText();

        try {
            CentralActions.MutationResult result =
                    delete
                            ? centralActions.deleteTeamAssignmentByNumber(
                                    prefix,
                                    registrationNumber)
                            : centralActions.setTeamAssignmentByNumber(
                                    prefix,
                                    registrationNumber,
                                    teamId);
            raceFeedback.setText(
                    mutationFeedback(
                            "Team Mapping",
                            result));
            refresh();
        } catch (RuntimeException ex) {
            showError(raceFeedback, ex);
        }
    }

    private void applyStageStart(boolean delete) {
        try {
            int stageId =
                    Integer.parseInt(
                            raceStage.getText().trim());
            CentralActions.MutationResult result;
            if (delete) {
                result =
                        centralActions.deleteStageStartRangeByNumber(
                                stageId,
                                raceStagePrefix.getValue(),
                                raceRegistrationFrom.getText(),
                                raceRegistrationTo.getText());
            } else {
                long intervalMillis =
                        Math.multiplyExact(
                                Long.parseLong(
                                        raceStageIntervalSeconds.getText().trim()),
                                1000L);
                result =
                        centralActions.setStageStartRangeByNumber(
                                stageId,
                                raceStagePrefix.getValue(),
                                raceRegistrationFrom.getText(),
                                raceRegistrationTo.getText(),
                                raceStageStart.getText(),
                                intervalMillis);
            }
            raceFeedback.setText(
                    mutationFeedback(
                            "Stage " + stageId + " start times",
                            result));
            refresh();
        } catch (NumberFormatException ex) {
            raceFeedback.setText(
                    "StageId and interval seconds must be integers");
        } catch (ArithmeticException ex) {
            raceFeedback.setText("Interval seconds is too large");
        } catch (RuntimeException ex) {
            showError(raceFeedback, ex);
        }
    }

    static String mutationFeedback(
            String subject,
            CentralActions.MutationResult result) {
        if (result.immediatePackets() == 0) {
            return subject
                    + ": "
                    + result.affected()
                    + " updated; retained in Central state";
        }
        return subject
                + ": "
                + result.affected()
                + " updated; delivery started with "
                + result.immediatePackets()
                + " DN_PACKET"
                + (result.immediatePackets() == 1 ? "" : "s");
    }

    private void querySourceHead() {
        String nodeId = timingNode.getValue();
        try {
            long packetNr =
                    centralActions.requestSourceHead(nodeId);
            timingFeedback.setText(
                    packetNr == 0L
                            ? "SEQ REQ queued behind pending DN_PACKET"
                            : "DN_PACKET #" + packetNr
                                    + " SEQ REQ queued");
            refresh();
        } catch (RuntimeException ex) {
            showError(timingFeedback, ex);
        }
    }

    private void requestTimingData() {
        String nodeId = timingNode.getValue();
        try {
            long fromSequence =
                    Long.parseLong(
                            timingFromSequence.getText().trim());
            long packetNr =
                    centralActions.requestTimingData(
                            nodeId,
                            fromSequence);
            timingFeedback.setText(
                    packetNr == 0L
                            ? "TIMING_DATA_REQ queued behind pending DN_PACKET"
                            : "DN_PACKET #" + packetNr
                                    + " TIMING_DATA_REQ from "
                                    + fromSequence
                                    + " queued");
            refresh();
        } catch (NumberFormatException ex) {
            timingFeedback.setText("From sequence must be an integer");
        } catch (RuntimeException ex) {
            showError(timingFeedback, ex);
        }
    }

    private static void showError(
            Label feedback,
            RuntimeException ex) {
        String message = ex.getMessage();
        feedback.setText(
                message == null || message.isBlank()
                        ? ex.getClass().getSimpleName()
                        : message);
    }

    private void refreshNodeSelectors(
            List<CentralSystemSimulator.NodeView> nodeViews) {
        List<String> nodeIds = nodeViews.stream()
                .map(CentralSystemSimulator.NodeView::nodeId)
                .toList();
        refreshNodeSelector(timingNode, nodeIds);
    }

    private static void refreshNodeSelector(
            ComboBox<String> selector,
            List<String> nodeIds) {
        String selected = selector.getValue();
        selector.setItems(FXCollections.observableArrayList(nodeIds));

        if (selected != null && nodeIds.contains(selected)) {
            selector.setValue(selected);
        } else if (!nodeIds.isEmpty()) {
            selector.setValue(nodeIds.get(0));
        } else {
            selector.setValue(null);
        }
    }

    private void refreshRaceDataState() {
        raceTeamPrefix.setDisable(false);
        raceRegistration.setDisable(false);
        raceTeam.setDisable(false);
        raceStage.setDisable(false);
        raceStagePrefix.setDisable(false);
        raceRegistrationFrom.setDisable(false);
        raceRegistrationTo.setDisable(false);
        raceStageStart.setDisable(false);
        raceStageIntervalSeconds.setDisable(false);
        raceSet.setDisable(false);
        raceDelete.setDisable(false);
        stageStartSet.setDisable(false);
        stageStartDelete.setDisable(false);
        if (!simulator.isConnected()) {
            raceFeedback.setText(
                    "Central authoring available offline; delivery waits for an upstream connection");
        }
    }

    private void refreshTimingDataState() {
        String selected = timingNode.getValue();
        CentralSystemSimulator.NodeView view = simulator.nodes().stream()
                .filter(value -> value.nodeId().equals(selected))
                .findFirst()
                .orElse(null);

        timingHead.setText(
                view == null
                        ? "0"
                        : Long.toString(view.timingDataHead()));
        timingSourceHead.setText(
                view == null || view.sourceHead() == null
                        ? "—"
                        : Long.toString(view.sourceHead().longValue()));
        timingDnAck.setText(
                view == null
                        ? "0"
                        : Long.toString(view.lastDnPacketAcknowledged()));
        timingDnPending.setText(
                view == null || view.outstandingDnPacketNr() == null
                        ? "—"
                        : Long.toString(view.outstandingDnPacketNr()));

        boolean enabled = simulator.isConnected() && view != null;
        timingNode.setDisable(
                !simulator.isConnected()
                        || simulator.nodes().isEmpty());
        timingSourceHeadQuery.setDisable(
                !enabled
                        || view.sourceHeadQueryPending());
        timingSourceHeadQuery.setText(
                view != null && view.sourceHeadQueryPending()
                        ? "Querying…"
                        : "Query SI-01");
        timingFromSequence.setDisable(!enabled);
        timingRequest.setDisable(!enabled);

        if (!simulator.isConnected()) {
            timingFeedback.setText("Central upstream not connected");
        } else if (view == null) {
            timingFeedback.setText("No Central NodeId observed yet");
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
