package io.github.brainboxemb.eventtiming.testclient;

import javafx.geometry.Orientation;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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
    private final Map<DockContainerLeaf, DockContainerBranch> homeParents = new LinkedHashMap<>();
    private final Map<DockContainerBranch, List<DockContainerLeaf>> homeOrdering = new LinkedHashMap<>();

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
        bento.controlsBuilding().setHeaderFactory((dockable, parentPane) -> {
            var header = new CompactBentoHeader(dockable, parentPane).withDragDrop();
            ContextMenu menu = new ContextMenu();
            MenuItem dockBack = new MenuItem("↩  Dock back to original position");
            dockBack.setOnAction(event -> dockBack(dockable.getIdentifier()));
            menu.getItems().add(dockBack);
            menu.setOnShowing(event -> {
                DockContainerLeaf home = homeLeaves.get(dockable.getIdentifier());
                dockBack.setDisable(home == null || dockable.getContainer() == home);
            });
            header.setOnContextMenuRequested(event -> {
                menu.show(header, event.getScreenX(), event.getScreenY());
                event.consume();
            });
            return header;
        });

        DockBuilding builder = bento.dockBuilding();
        root = builder.root("engineering-root");

        DockContainerBranch leftColumn = builder.branch("left-column");
        DockContainerBranch leftTop = builder.branch("left-top");
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
        leftTop.setOrientation(Orientation.HORIZONTAL);
        rightColumn.setOrientation(Orientation.VERTICAL);

        root.addContainers(leftColumn, rightColumn);
        leftColumn.addContainers(leftTop, deviceLogLeaf, terminalLeaf, clientLogLeaf);
        leftTop.addContainers(systemsLeaf, controls);
        rightColumn.addContainers(registrationsLeaf, logBookLeaf, detailLeaf);

        homeOrdering.put(leftTop, List.of(systemsLeaf, controls));
        homeOrdering.put(leftColumn,
                List.of(deviceLogLeaf, terminalLeaf, clientLogLeaf));
        homeOrdering.put(rightColumn,
                List.of(registrationsLeaf, logBookLeaf, detailLeaf));
        for (Map.Entry<DockContainerBranch, List<DockContainerLeaf>> group
                : homeOrdering.entrySet()) {
            for (DockContainerLeaf leaf : group.getValue()) {
                homeParents.put(leaf, group.getKey());
            }
        }

        configureLeaf(systemsLeaf);
        configureLeaf(controls);
        configureLeaf(deviceLogLeaf);
        configureLeaf(terminalLeaf);
        configureLeaf(clientLogLeaf);
        configureLeaf(registrationsLeaf);
        configureLeaf(logBookLeaf);
        configureLeaf(detailLeaf);

        // The leaf must disappear when its only panel is detached; otherwise
        // a blank column remains until the user manually resets the layout.
        // Keep the split between Explorer and controls intact while either
        // pane remains. A detached Systems pane then leaves controls visible
        // at full width and is reconstructed in its original slot on re-pin.
        leftTop.setPruneWhenEmpty(false);
        // Keep both column branches stable as docking homes for re-pinning.
        leftColumn.setPruneWhenEmpty(false);
        rightColumn.setPruneWhenEmpty(false);

        root.setDividerPositions(0.49);
        leftColumn.setDividerPositions(0.43, 0.64, 0.82);
        leftTop.setDividerPositions(0.28);
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
        restoreHomeArea(home);
        home.addDockable(panel);
        home.selectDockable(panel);
    }

    /** A pruned leaf must be reinserted at its normal position before repinning. */
    private void restoreHomeArea(DockContainerLeaf home) {
        DockContainerBranch parent = homeParents.get(home);
        if (parent == null || home.getParentContainer() == parent) return;
        List<DockContainerLeaf> order = homeOrdering.get(parent);
        int targetIndex = order.indexOf(home);
        int insertAt = 0;
        for (int i = 0; i < targetIndex; i++) {
            if (order.get(i).getParentContainer() == parent) insertAt++;
        }
        parent.addContainer(insertAt, home);
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
        // Systems is the persistent Explorer: closing it has no useful
        // meaning, and an X would imply a missing reopen action.
        if ("systems".equals(panel.id())) dockable.setClosable(false);
        dockable.setNode(panel.content());
        dockable.setDragGroupMask(WORKBENCH_DRAG_GROUP);
        dockable.setIconFactory(current -> {
            Button pin = new Button("↩");
            pin.getStyleClass().add("dock-back-button");
            pin.setAccessibleText("Dock " + panel.title() + " back");
            pin.setTooltip(new Tooltip("Dock back to original position"));
            pin.setFocusTraversable(false);
            // Track the dockable's owner directly. Watching the JavaFX Scene
            // misses the moment a newly detached window is created.
            Runnable updateVisibility = () -> {
                boolean awayFromHome = current.getContainer() != home;
                pin.setVisible(awayFromHome);
                pin.setManaged(awayFromHome);
            };
            current.containerProperty().addListener(
                    (observable, oldContainer, newContainer) -> updateVisibility.run());
            pin.setOnAction(event -> dockBack(panel.id()));
            updateVisibility.run();
            return pin;
        });
        dockables.put(panel.id(), dockable);
        homeLeaves.put(panel.id(), home);
        return dockable;
    }
}
