package io.github.brainboxemb.eventtiming.testclient;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Development Client controls for repeatable simulated-tag batches.
 *
 * <p>The pane owns only client-side batch selection and pacing. SI-01 owns each
 * individual simulated-tag scenario and its observation profile.</p>
 */
final class SimulatedTagsPane extends TitledPane {
    private final Supplier<ApiClient> clientSupplier;
    private final ExecutorService requests;
    private final Supplier<String> selectedNodeSupplier;
    private final Consumer<String> rawSink;
    private final Consumer<String> feedback;
    private final Consumer<String> apiState;
    private final Consumer<Throwable> commandError;
    private final Runnable stateChanged;
    private final ClientLog clientLog;

    private final Label capability =
            new Label("Capability not loaded");
    private final ComboBox<String> profile =
            new ComboBox<>();
    private final TextField count =
            new TextField("100");
    private final TextField rangeFrom =
            new TextField("1");
    private final TextField rangeTo =
            new TextField("2000");
    private final ComboBox<String> order =
            new ComboBox<>();
    private final TextField seed =
            new TextField("1");
    private final TextField interval =
            new TextField("500");
    private final Button start =
            new Button("Start");
    private final Button stop =
            new Button("Stop");
    private final Label progress =
            new Label("-");
    private final PauseTransition delay =
            new PauseTransition();

    private List<String> batch =
            List.of();
    private int batchIndex;
    private long intervalMillis;
    private long requestStartedNanos;
    private boolean running;
    private String batchProfile;
    private String batchNodeId;

    SimulatedTagsPane(
            Supplier<ApiClient> clientSupplier,
            ExecutorService requests,
            Supplier<String> selectedNodeSupplier,
            Consumer<String> rawSink,
            Consumer<String> feedback,
            Consumer<String> apiState,
            Consumer<Throwable> commandError,
            Runnable stateChanged,
            ClientLog clientLog) {
        if (clientSupplier == null
                || requests == null
                || selectedNodeSupplier == null
                || rawSink == null
                || feedback == null
                || apiState == null
                || commandError == null
                || stateChanged == null
                || clientLog == null) {
            throw new IllegalArgumentException(
                    "SimulatedTagsPane dependencies must not be null");
        }

        this.clientSupplier = clientSupplier;
        this.requests = requests;
        this.selectedNodeSupplier = selectedNodeSupplier;
        this.rawSink = rawSink;
        this.feedback = feedback;
        this.apiState = apiState;
        this.commandError = commandError;
        this.stateChanged = stateChanged;
        this.clientLog = clientLog;

        profile.setItems(
                FXCollections.observableArrayList(
                        "simple",
                        "normal",
                        "edge"));
        profile.setValue(
                "normal");

        order.setItems(
                FXCollections.observableArrayList(
                        "Ascending",
                        "Random"));
        order.setValue(
                "Ascending");

        count.setPrefColumnCount(6);
        rangeFrom.setPrefColumnCount(6);
        rangeTo.setPrefColumnCount(6);
        seed.setPrefColumnCount(8);
        interval.setPrefColumnCount(8);
        interval.setTooltip(
                new Tooltip(
                        "Milliseconds between registration scenario starts. "
                                + "The selected profile owns observation timing inside each passage."));

        GridPane grid =
                new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        add(grid, 0, "Profile", profile);
        add(grid, 1, "Count", count);
        grid.add(
                new Label("Number range"),
                0,
                2);
        grid.add(
                new HBox(
                        8,
                        rangeFrom,
                        new Label("to"),
                        rangeTo),
                1,
                2);
        grid.add(
                new Label("Order"),
                0,
                3);
        grid.add(
                new HBox(
                        8,
                        order,
                        new Label("Seed"),
                        seed),
                1,
                3);
        add(grid, 4, "Interval ms", interval);
        grid.add(
                new HBox(
                        8,
                        start,
                        stop),
                1,
                5);
        grid.add(
                new HBox(
                        8,
                        new Label("Progress"),
                        progress),
                1,
                6);

        VBox content =
                new VBox(
                        8,
                        capability,
                        grid);
        content.setPadding(
                new Insets(10));

        setText(
                "Simulated tags");
        setContent(
                content);
        setCollapsible(
                true);

        start.setOnAction(
                event -> startBatch());
        stop.setOnAction(
                event -> stopBatch());
        order.setOnAction(
                event -> refreshControls());
        refresh(
                false,
                false);
    }

    boolean running() {
        return running;
    }

    void refresh(
            boolean live,
            boolean enabled) {
        capability.setText(
                !live
                        ? "Capability state cached/not synchronised"
                        : enabled
                                ? "TAG_SCENARIO_SIMULATION enabled"
                                : "TAG_SCENARIO_SIMULATION unavailable");

        boolean fieldsDisabled =
                !live
                        || !enabled
                        || running;
        profile.setDisable(
                fieldsDisabled);
        count.setDisable(
                fieldsDisabled);
        rangeFrom.setDisable(
                fieldsDisabled);
        rangeTo.setDisable(
                fieldsDisabled);
        order.setDisable(
                fieldsDisabled);
        seed.setDisable(
                fieldsDisabled
                        || !"Random".equals(
                                order.getValue()));
        interval.setDisable(
                fieldsDisabled);
        start.setDisable(
                !live
                        || !enabled
                        || running);
        stop.setDisable(
                !running);
    }

    void stopBatch() {
        if (!running) {
            return;
        }
        running = false;
        delay.stop();
        progress.setText(
                "Stopped at "
                        + batchIndex
                        + " / "
                        + batch.size());
        feedback.accept(
                "Simulated-tag batch stopped");
        clientLog.info(
                "Stopped simulated-tag batch at "
                        + batchIndex
                        + " of "
                        + batch.size());
        stateChanged.run();
    }

    private void startBatch() {
        SimulationBatchPlan.Order selectedOrder =
                "Random".equals(
                        order.getValue())
                        ? SimulationBatchPlan.Order.RANDOM
                        : SimulationBatchPlan.Order.ASCENDING;

        final int requestedCount;
        final int from;
        final int to;
        final long selectedSeed;
        final long requestedInterval;
        try {
            requestedCount =
                    positiveInteger(
                            count,
                            "Count");
            from =
                    positiveInteger(
                            rangeFrom,
                            "Range start");
            to =
                    positiveInteger(
                            rangeTo,
                            "Range end");
            selectedSeed =
                    selectedOrder
                            == SimulationBatchPlan.Order.RANDOM
                            ? Long.parseLong(
                                    seed.getText()
                                            .trim())
                            : 0L;
            requestedInterval =
                    positiveLong(
                            interval,
                            "Interval");
        } catch (IllegalArgumentException ex) {
            progress.setText(
                    ex.getMessage());
            return;
        }

        final List<String> registrations;
        try {
            registrations =
                    SimulationBatchPlan.registrationIds(
                            requestedCount,
                            from,
                            to,
                            selectedOrder,
                            selectedSeed);
        } catch (IllegalArgumentException ex) {
            progress.setText(
                    ex.getMessage());
            return;
        }

        String selectedNode =
                selectedNodeSupplier.get();
        if (selectedNode == null
                || selectedNode.trim().isEmpty()) {
            progress.setText(
                    "No TimingNode selected");
            return;
        }

        batch =
                registrations;
        batchIndex = 0;
        intervalMillis =
                requestedInterval;
        batchProfile =
                profile.getValue();
        batchNodeId =
                selectedNode;
        running = true;
        progress.setText(
                "0 / "
                        + batch.size());
        clientLog.info(
                "Starting simulated-tag batch profile="
                        + batchProfile
                        + " count="
                        + batch.size()
                        + " order="
                        + selectedOrder
                        + " intervalMs="
                        + intervalMillis);
        feedback.accept(
                "Starting simulated-tag batch...");
        stateChanged.run();
        sendNext();
    }

    private void sendNext() {
        if (!running) {
            return;
        }
        if (batchIndex
                >= batch.size()) {
            finishBatch();
            return;
        }

        final String registrationId =
                batch.get(
                        batchIndex);
        final int requestNumber =
                batchIndex + 1;

        progress.setText(
                requestNumber
                        + " / "
                        + batch.size()
                        + " — "
                        + registrationId);
        requestStartedNanos =
                System.nanoTime();

        CompletableFuture
                .supplyAsync(
                        () -> {
                            try {
                                return clientSupplier
                                        .get()
                                        .simulateRegistration(
                                                batchNodeId,
                                                registrationId,
                                                batchProfile);
                            } catch (Exception ex) {
                                throw new CompletionException(
                                        ex);
                            }
                        },
                        requests)
                .whenComplete(
                        (result, error) ->
                                Platform.runLater(
                                        () -> completeRequest(
                                                result,
                                                error)));
    }

    private void completeRequest(
            ApiClient.OperationResult result,
            Throwable error) {
        if (error != null) {
            running = false;
            delay.stop();
            progress.setText(
                    "Stopped at "
                            + batchIndex
                            + " / "
                            + batch.size());
            commandError.accept(
                    error);
            stateChanged.run();
            return;
        }

        apiState.accept(
                "READY");
        rawSink.accept(
                result.rawJson());
        batchIndex++;
        progress.setText(
                batchIndex
                        + " / "
                        + batch.size()
                        + " accepted");

        if (!running) {
            stateChanged.run();
            return;
        }
        if (batchIndex
                >= batch.size()) {
            finishBatch();
            return;
        }

        long elapsedNanos =
                Math.max(
                        0L,
                        System.nanoTime()
                                - requestStartedNanos);
        double remainingMillis =
                Math.max(
                        0.0,
                        intervalMillis
                                - elapsedNanos
                                / 1_000_000.0);

        delay.stop();
        delay.setDuration(
                Duration.millis(
                        remainingMillis));
        delay.setOnFinished(
                event -> sendNext());
        delay.playFromStart();
    }

    private void finishBatch() {
        running = false;
        delay.stop();
        progress.setText(
                "Complete "
                        + batch.size()
                        + " / "
                        + batch.size());
        feedback.accept(
                "Simulated-tag batch complete");
        clientLog.info(
                "Completed simulated-tag batch count="
                        + batch.size());
        stateChanged.run();
    }

    private void refreshControls() {
        boolean disabled =
                start.isDisabled();
        /*
         * Preserve the current LIVE/capability state owned by refresh(): only
         * the seed dependency changes when the order combo changes.
         */
        seed.setDisable(
                disabled
                        || running
                        || !"Random".equals(
                                order.getValue()));
    }

    private static int positiveInteger(
            TextField field,
            String name) {
        try {
            int value =
                    Integer.parseInt(
                            field.getText()
                                    .trim());
            if (value < 1) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    name + " must be a positive integer");
        }
    }

    private static long positiveLong(
            TextField field,
            String name) {
        try {
            long value =
                    Long.parseLong(
                            field.getText()
                                    .trim());
            if (value < 1L) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    name + " must be a positive integer");
        }
    }

    private static void add(
            GridPane grid,
            int row,
            String label,
            javafx.scene.Node value) {
        grid.add(
                new Label(label),
                0,
                row);
        grid.add(
                value,
                1,
                row);
    }
}
