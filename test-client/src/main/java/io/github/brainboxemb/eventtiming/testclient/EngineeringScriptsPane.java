package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** SI-02-only script editor and runner. No scripting support in SI-01. */
final class EngineeringScriptsPane extends VBox implements AutoCloseable {
    private static final int MAX_SCRIPT_BYTES = 128 * 1024;
    private static final String STARTER =
            "-- Client-side Lua: executes against selected LIVE TimingNode via IF-03\n"
            + "timing.batch{count=10, from=1, to=2000, order='linear',\n"
            + "             intervalMs=500, profile='normal'}\n";

    private final Supplier<ApiClient> clientSupplier;
    private final Supplier<String> selectedNode;
    private final BooleanSupplier guiBatchRunning;
    private final Runnable refreshControls;
    private final ClientLog clientLog;

    private final TextArea source = new TextArea(STARTER);
    private final TextArea output = new TextArea();
    private final Label state = new Label("Load a .lua file or edit the script");
    private final Button open = new Button("Open .lua");
    private final Button run = new Button("Run");
    private final Button stop = new Button("Stop");
    private final ExecutorService runner = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "engineering-lua-script");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean validRun = new AtomicBoolean();
    private volatile boolean running;
    private boolean available;
    private boolean simulationAvailable;
    private String runningNode;
    private Future<?> job;
    private long generation;

    EngineeringScriptsPane(Supplier<ApiClient> clientSupplier,
                           Supplier<String> selectedNode,
                           BooleanSupplier guiBatchRunning,
                           Runnable refreshControls,
                           ClientLog clientLog) {
        this.clientSupplier = java.util.Objects.requireNonNull(clientSupplier);
        this.selectedNode = java.util.Objects.requireNonNull(selectedNode);
        this.guiBatchRunning = java.util.Objects.requireNonNull(guiBatchRunning);
        this.refreshControls = java.util.Objects.requireNonNull(refreshControls);
        this.clientLog = java.util.Objects.requireNonNull(clientLog);

        source.setWrapText(false);
        source.setPromptText("Enter Lua script or drop a .lua file");
        source.getStyleClass().add("engineering-script-editor");
        output.setEditable(false);
        output.setWrapText(false);
        output.getStyleClass().add("engineering-log");
        HBox toolbar = new HBox(8, open, run, stop, state);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        SplitPane editors = new SplitPane(
                new VBox(new Label("Script"), source),
                new VBox(new Label("Output"), output));
        editors.setOrientation(Orientation.VERTICAL);
        editors.setDividerPositions(0.65);
        VBox.setVgrow(editors, Priority.ALWAYS);
        for (javafx.scene.Node child : editors.getItems()) {
            VBox box = (VBox) child;
            VBox.setVgrow(box.getChildren().get(1), Priority.ALWAYS);
        }
        getChildren().addAll(toolbar, editors);
        getStyleClass().add("engineering-data-pane");
        setSpacing(4);

        open.setOnAction(event -> chooseFile());
        run.setOnAction(event -> runScript());
        stop.setOnAction(event -> stopScript());
        run.setTooltip(new Tooltip(
                "Run Lua only in this Engineering Client. Commands use IF-03."));
        stop.setTooltip(new Tooltip("Stop running batch/simulation script"));
        setOnDragOver(event -> {
            if (!running && matchingLuaFile(event.getDragboard()) != null) {
                event.acceptTransferModes(TransferMode.COPY);
                event.consume();
            }
        });
        setOnDragDropped(event -> {
            Path file = matchingLuaFile(event.getDragboard());
            boolean success = !running && file != null && loadFile(file);
            event.setDropCompleted(success);
            event.consume();
        });
        refresh(false, false, null);
    }

    private static Path matchingLuaFile(Dragboard board) {
        if (!board.hasFiles()) return null;
        for (java.io.File f : board.getFiles()) {
            if (f.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".lua")) {
                return f.toPath();
            }
        }
        return null;
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Engineering Lua Script");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Lua scripts (*.lua)", "*.lua"));
        java.io.File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) loadFile(file.toPath());
    }

    private boolean loadFile(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_SCRIPT_BYTES) {
                throw new IOException("Script file must be <= 128 KiB");
            }
            source.setText(Files.readString(path, StandardCharsets.UTF_8));
            state.setText("Loaded: " + path.getFileName());
            return true;
        } catch (IOException error) {
            state.setText("Open failed: " + error.getMessage());
            clientLog.warn("Lua open failed: " + error.getMessage());
            return false;
        }
    }

    boolean running() {
        return running;
    }

    /** Called from the FX owner after status, selection or capability changes. */
    void refresh(boolean live, boolean simulationEnabled, String nodeId) {
        simulationAvailable = simulationEnabled;
        available = live && nodeId != null
                && !nodeId.isBlank() && !guiBatchRunning.getAsBoolean();
        if (!running) {
            state.setText(!live ? "Scripts require Events LIVE (connect and sync)"
                    : nodeId == null || nodeId.isBlank() ? "Select a TimingNode"
                    : guiBatchRunning.getAsBoolean() ? "GUI simulation batch is running"
                    : !simulationEnabled ? "Lua ready; simulation unavailable (TAG_SCENARIO_SIMULATION)"
                    : "Lua ready (simulation available)");
        }
        if (running && (!live || !simulationEnabled
                || !java.util.Objects.equals(nodeId, runningNode))) {
            stopScript();
        }
        run.setDisable(running || !available);
        stop.setDisable(!running);
        open.setDisable(running);
        source.setEditable(!running);
    }

    private void runScript() {
        if (running || !available) return;
        String nodeId = selectedNode.get(); // FX-thread snapshot.
        if (nodeId == null || nodeId.isBlank() || guiBatchRunning.getAsBoolean()) return;
        if (source.getText().getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES) {
            state.setText("Script must be <= 128 KiB");
            return;
        }
        final ApiClient client = clientSupplier.get(); // target is fixed for this run.
        final String script = source.getText();
        final boolean canSimulate = simulationAvailable;
        final long currentGeneration = ++generation;
        runningNode = nodeId;
        running = true;
        validRun.set(true);
        output.clear();
        state.setText("Running on node " + nodeId);
        clientLog.info("Running Engineering Lua script on node " + nodeId);
        refreshControls.run();
        job = runner.submit(() -> {
            try {
                EngineeringLuaEngine.run(script,
                        (regId, profile) -> {
                            if (!validRun.get()) throw new EngineeringLuaEngine.Stopped();
                            if (!canSimulate) {
                                throw new IllegalStateException("TAG_SCENARIO_SIMULATION unavailable; "
                                        + "start SI-01 with config/development-simulation.yml");
                            }
                            client.simulateRegistration(nodeId, regId, profile);
                        },
                        line -> Platform.runLater(() -> {
                            if (currentGeneration == generation)
                                output.appendText(line + "\n");
                        }),
                        validRun::get);
                finish(currentGeneration, "Complete");
            } catch (EngineeringLuaEngine.Stopped stopped) {
                finish(currentGeneration, "Stopped");
            } catch (Throwable ex) {
                String reason = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                clientLog.error("Lua script failed: " + reason);
                finish(currentGeneration, "Error: " + reason);
            }
        });
    }

    private void finish(long token, String message) {
        Platform.runLater(() -> {
            if (token != generation) return;
            running = false;
            validRun.set(false);
            job = null;
            refreshControls.run();
            state.setText(message);
            output.appendText(message + "\n");
        });
    }

    void stopScript() {
        if (!running) return;
        validRun.set(false);
        if (job != null) job.cancel(true);
        state.setText("Stopping…");
    }

    @Override
    public void close() {
        stopScript();
        runner.shutdownNow();
    }
}
