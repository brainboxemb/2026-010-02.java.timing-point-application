package io.github.brainboxemb.eventtiming.testclient;

import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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

    // As in Recaf, configure *every* leaf through BentoFX's builder seam.
    // Dragging a tab to a new leaf or floating window must not lose the
    // standard ≡ configuration menu. BentoFX copies this factory when it
    // creates a standalone DragDropStage.
    private final Bento bento = new Bento() {
        @Override
        protected DockBuilding newDockBuilding() {
            return new DockBuilding(this) {
                @Override
                public DockContainerLeaf leaf(String identifier) {
                    DockContainerLeaf leaf = super.leaf(identifier);
                    leaf.setMenuFactory(EngineeringWorkbench.this::buildDockMenu);
                    return leaf;
                }
            };
        }
    };
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
        bento.controlsBuilding().setHeaderFactory(
                (dockable, parentPane) ->
                        new CompactBentoHeader(dockable, parentPane).withDragDrop());

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

    /**
     * Standard BentoFX header-area ≡ menu. The ▼ overflow selector is
     * managed by HeaderPane; no custom tab toolbar is necessary.
     */
    private ContextMenu buildDockMenu(DockContainerLeaf leaf) {
        ContextMenu menu = new ContextMenu();
        Dockable selected = leaf.getSelectedDockable();
        if (selected != null) {
            DockContainerLeaf home = homeLeaves.get(selected.getIdentifier());
            if (home != null && home != selected.getContainer()) {
                MenuItem dockBack = new MenuItem("Dock selected tab back");
                dockBack.setOnAction(event -> dockBack(selected.getIdentifier()));
                menu.getItems().addAll(dockBack, new SeparatorMenuItem());
            }
        }

        Menu tabPosition = new Menu("Tab position");
        ToggleGroup positionGroup = new ToggleGroup();
        for (Side side : Side.values()) {
            RadioMenuItem item = new RadioMenuItem(switch (side) {
                case TOP -> "Top";
                case BOTTOM -> "Bottom";
                case LEFT -> "Left";
                case RIGHT -> "Right";
            });
            item.setToggleGroup(positionGroup);
            item.setSelected(leaf.getSide() == side);
            item.setOnAction(event -> leaf.setSide(side));
            tabPosition.getItems().add(item);
        }
        menu.getItems().add(tabPosition);
        return menu;
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
        // All built-in SI-02 views are permanent until the workbench has a
        // proper Window/Show panel menu. BentoFX defaults to closable tabs,
        // which displays a misleading X and can remove a live diagnostic pane
        // with no obvious way to get it back. This supported property also
        // prevents the tab close action, not just its visual decoration.
        dockable.setClosable(false);
        dockable.setNode(panel.content());
        dockable.setDragGroupMask(WORKBENCH_DRAG_GROUP);
        dockables.put(panel.id(), dockable);
        homeLeaves.put(panel.id(), home);
        return dockable;
    }
}
