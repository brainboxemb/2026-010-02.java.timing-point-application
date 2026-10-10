package io.github.brainboxemb.eventtiming.testclient;

import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import software.coley.bentofx.control.DragDropStage;
import java.util.LinkedHashMap;
import java.util.Map;
import javafx.geometry.Side;
import javafx.scene.Node;
import software.coley.bentofx.Bento;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.layout.container.DockContainerRootBranch;

/**
 * BentoFX composition for the SI-02 Engineering Desktop Client.
 *
 * <p>All supplied content remains ordinary JavaFX. BentoFX is isolated to this
 * composition layer so functional panes do not depend on docking APIs.</p>
 */
final class EngineeringWorkbench {
    private static final int WORKBENCH_DRAG_GROUP = 1;

    private final Bento bento = new Bento();
    private final DockContainerRootBranch root;
    private final Map<String, Dockable> dockables = new LinkedHashMap<>();
    private final Map<String, DockContainerLeaf> homeLeaves = new LinkedHashMap<>();

    EngineeringWorkbench(
            Node systems,
            Node timingNode,
            Node registration,
            Node simulation,
            Node deviceLog,
            Node terminal,
            Node clientLog,
            Node registrations,
            Node logBook,
            Node rawData,
            Node events) {

        bento.stageBuilding().setApplySourceAsOwner(false);
        bento.stageBuilding().setApplyMousePosition(true);
        bento.controlsBuilding().setHeaderFactory(
                (dockable, parentPane) ->
                        new CompactBentoHeader(dockable, parentPane).withDragDrop());

        DockBuilding builder = bento.dockBuilding();
        root = builder.root("engineering-root");

        DockContainerBranch leftColumn = builder.branch("left-column");
        DockContainerLeaf systemsLeaf = builder.leaf("systems");
        DockContainerBranch rightColumn = builder.branch("right-column");

        DockContainerLeaf controls = builder.leaf("controls");
        DockContainerLeaf deviceLogLeaf = builder.leaf("device-log");
        DockContainerLeaf terminalLeaf = builder.leaf("terminal");
        DockContainerLeaf clientLogLeaf = builder.leaf("client-log");

        DockContainerLeaf registrationsLeaf = builder.leaf("registrations");
        DockContainerLeaf logBookLeaf = builder.leaf("logbook");
        DockContainerLeaf detailLeaf = builder.leaf("detail");

        root.setOrientation(Orientation.HORIZONTAL);
        leftColumn.setOrientation(Orientation.VERTICAL);
        rightColumn.setOrientation(Orientation.VERTICAL);

        root.addContainers(systemsLeaf, leftColumn, rightColumn);
        leftColumn.addContainers(controls, deviceLogLeaf, terminalLeaf, clientLogLeaf);
        rightColumn.addContainers(registrationsLeaf, logBookLeaf, detailLeaf);

        configureLeaf(systemsLeaf);
        configureLeaf(controls);
        configureLeaf(deviceLogLeaf);
        configureLeaf(terminalLeaf);
        configureLeaf(clientLogLeaf);
        configureLeaf(registrationsLeaf);
        configureLeaf(logBookLeaf);
        configureLeaf(detailLeaf);

        systemsLeaf.setPruneWhenEmpty(false);
        leftColumn.setPruneWhenEmpty(false);
        rightColumn.setPruneWhenEmpty(false);

        root.setDividerPositions(0.18, 0.57);
        leftColumn.setDividerPositions(0.44, 0.64, 0.83);
        rightColumn.setDividerPositions(0.46, 0.76);

        systemsLeaf.addDockable(
                dockable(builder, systemsLeaf, new WorkbenchPanel("systems", "Systems", systems)));

        controls.addDockables(
                dockable(builder, controls, new WorkbenchPanel("timing-node", "TimingNode", timingNode)),
                dockable(builder, controls, new WorkbenchPanel("registration", "Registration", registration)),
                dockable(builder, controls, new WorkbenchPanel("simulation", "Simulation", simulation)));

        deviceLogLeaf.addDockable(
                dockable(builder, deviceLogLeaf, new WorkbenchPanel("device-log", "Device Log", deviceLog)));
        terminalLeaf.addDockable(
                dockable(builder, terminalLeaf, new WorkbenchPanel("terminal", "Terminal", terminal)));
        clientLogLeaf.addDockable(
                dockable(builder, clientLogLeaf, new WorkbenchPanel("client-log", "Client Log", clientLog)));

        registrationsLeaf.addDockable(
                dockable(builder, registrationsLeaf, new WorkbenchPanel("registrations", "Registrations", registrations)));
        logBookLeaf.addDockable(
                dockable(builder, logBookLeaf, new WorkbenchPanel("logbook", "LogBook", logBook)));
        detailLeaf.addDockables(
                dockable(builder, detailLeaf, new WorkbenchPanel("raw-data", "Raw Data", rawData)),
                dockable(builder, detailLeaf, new WorkbenchPanel("events", "Events", events)));
    }

    DockContainerRootBranch root() {
        return root;
    }

    private static void configureLeaf(DockContainerLeaf leaf) {
        leaf.setSide(Side.TOP);
    }

    void setDeviceLogLevel(String level) {
        setTitle("device-log", "Device Log", level);
    }

    void setClientLogLevel(String level) {
        setTitle("client-log", "Client Log", level);
    }

    void setLogBookCount(long count) {
        Dockable panel = dockables.get("logbook");
        if (panel != null) panel.setTitle("LogBook (" + count + ")");
    }

    private void setTitle(String id, String name, String level) {
        Dockable panel = dockables.get(id);
        if (panel != null) {
            String code = switch (level == null ? "" : level) {
                case "TRACE" -> "T";
                case "DEBUG" -> "D";
                case "INFO" -> "I";
                case "WARN" -> "W";
                case "ERROR" -> "E";
                default -> "-";
            };
            panel.setTitle(name + " [" + code + "]");
        }
    }

    /** Restores one detached or rearranged panel to its original dock area. */
    void dockBack(String id) {
        Dockable panel = dockables.get(id);
        DockContainerLeaf home = homeLeaves.get(id);
        if (panel == null || home == null || panel.getContainer() == home) return;
        DockContainerLeaf current = panel.getContainer();
        if (current != null) current.removeDockable(panel);
        home.addDockable(panel);
        home.selectDockable(panel);
    }

    /** Release old dockables before rebuilding the canonical workbench tree. */
    void release() {
        for (Dockable dockable : dockables.values()) {
            dockable.setNode(null);
            DockContainerLeaf current = dockable.getContainer();
            if (current != null) current.removeDockable(dockable);
        }
    }

    private Dockable dockable(DockBuilding builder, DockContainerLeaf home, WorkbenchPanel panel) {
        Dockable dockable = builder.dockable(panel.id());
        dockable.setTitle(panel.title());
        dockable.setNode(panel.content());
        dockable.setDragGroupMask(WORKBENCH_DRAG_GROUP);
        dockable.setIconFactory(current -> {
            Button pin = new Button("↩");
            pin.getStyleClass().add("dock-back-button");
            pin.setAccessibleText("Dock " + panel.title() + " back");
            pin.setTooltip(new Tooltip("Dock back to original position"));
            pin.setFocusTraversable(false);
            Runnable updateVisibility = () -> {
                boolean floating = pin.getScene() != null
                        && pin.getScene().getWindow() instanceof DragDropStage;
                pin.setVisible(floating);
                pin.setManaged(floating);
            };
            pin.sceneProperty().addListener((observable, oldScene, newScene) -> {
                updateVisibility.run();
                if (newScene != null) newScene.windowProperty().addListener(
                        (ob, oldWindow, newWindow) -> updateVisibility.run());
            });
            pin.setOnAction(event -> dockBack(panel.id()));
            updateVisibility.run();
            return pin;
        });
        dockables.put(panel.id(), dockable);
        homeLeaves.put(panel.id(), home);
        return dockable;
    }
}
