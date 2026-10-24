package io.github.brainboxemb.eventtiming.testclient;

import javafx.geometry.Orientation;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import software.coley.bentofx.Bento;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.layout.container.DockContainerRootBranch;

/**
 * BentoFX composition for the SI-02 Engineering Desktop Client.
 *
 * <p>The main work area is split into two semantic perspectives for the selected
 * TimingNode: the direct Node/API viewpoint and the Central simulator viewpoint.
 * Each perspective owns an independent Bento layout. Device Log, Terminal and
 * Client Log remain shared engineering tools below both perspectives.</p>
 */
final class EngineeringWorkbench {
    private static final int WORKBENCH_DRAG_GROUP = 1;

    private final BooleanSupplier allRegistrations;
    private final Consumer<Boolean> setAllRegistrations;

    private final List<Bento> bentos = new ArrayList<>();
    private final Map<String, Dockable> dockables = new LinkedHashMap<>();
    private final Map<String, DockContainerLeaf> homeLeaves = new LinkedHashMap<>();
    private final Map<DockContainerLeaf, DockContainerBranch> homeParents =
            new LinkedHashMap<>();
    private final Map<DockContainerBranch, List<DockContainerLeaf>> homeOrdering =
            new LinkedHashMap<>();

    private final Tab nodePerspective = new Tab("Node");
    private final Tab centralPerspective = new Tab("Central");
    private final TabPane perspectives = new TabPane();
    private final SplitPane root = new SplitPane();

    EngineeringWorkbench(
            Node apiSystems,
            Node centralNodes,
            Node centralLocations,
            Node centralMessages,
            Node centralRaceData,
            Node timingNode,
            Node apiInfo,
            Node registration,
            Node simulation,
            Node scripts,
            Node deviceLog,
            Node terminal,
            Node clientLog,
            Node registrations,
            Node logBook,
            Node rawData,
            Node events,
            BooleanSupplier allRegistrations,
            Consumer<Boolean> setAllRegistrations) {
        this.allRegistrations = java.util.Objects.requireNonNull(allRegistrations);
        this.setAllRegistrations = java.util.Objects.requireNonNull(setAllRegistrations);

        nodePerspective.setClosable(false);
        centralPerspective.setClosable(false);
        nodePerspective.setContent(createNodePerspective(
                apiSystems,
                timingNode,
                apiInfo,
                registration,
                simulation,
                scripts,
                registrations,
                logBook,
                rawData,
                events));
        centralPerspective.setContent(createCentralPerspective(
                centralNodes,
                centralLocations,
                centralMessages,
                centralRaceData));

        perspectives.getTabs().setAll(nodePerspective, centralPerspective);
        perspectives.setSide(Side.TOP);
        perspectives.getStyleClass().add("engineering-perspectives");

        Node sharedTools = createSharedTools(deviceLog, terminal, clientLog);

        root.setOrientation(Orientation.VERTICAL);
        root.getItems().setAll(perspectives, sharedTools);
        root.setDividerPositions(0.78);
        root.getStyleClass().add("engineering-workbench-root");
    }

    Node root() {
        return root;
    }

    void setPerspectiveNodeId(String nodeId) {
        nodePerspective.setText(perspectiveTitle("Node", nodeId));
        centralPerspective.setText(perspectiveTitle("Central", nodeId));
    }

    static String perspectiveTitle(String perspective, String nodeId) {
        if (perspective == null || perspective.isBlank()) {
            throw new IllegalArgumentException("perspective must not be blank");
        }
        return nodeId == null || nodeId.isBlank()
                ? perspective
                : perspective + " " + nodeId.trim();
    }

    private Node createNodePerspective(
            Node apiSystems,
            Node timingNode,
            Node apiInfo,
            Node registration,
            Node simulation,
            Node scripts,
            Node registrations,
            Node logBook,
            Node rawData,
            Node events) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch nodeRoot = builder.root("node-perspective-root");
        DockContainerBranch left = builder.branch("node-left");
        DockContainerBranch right = builder.branch("node-right");

        DockContainerLeaf systemsLeaf = builder.leaf("node-systems");
        DockContainerLeaf controlsLeaf = builder.leaf("node-controls");
        DockContainerLeaf registrationsLeaf = builder.leaf("node-registrations");
        DockContainerLeaf logBookLeaf = builder.leaf("node-logbook");
        DockContainerLeaf detailLeaf = builder.leaf("node-detail");

        nodeRoot.setOrientation(Orientation.HORIZONTAL);
        left.setOrientation(Orientation.HORIZONTAL);
        right.setOrientation(Orientation.VERTICAL);

        nodeRoot.addContainers(left, right);
        left.addContainers(systemsLeaf, controlsLeaf);
        right.addContainers(registrationsLeaf, logBookLeaf, detailLeaf);

        registerHomeOrder(left, systemsLeaf, controlsLeaf);
        registerHomeOrder(right, registrationsLeaf, logBookLeaf, detailLeaf);

        configureLeaf(systemsLeaf);
        configureLeaf(controlsLeaf);
        configureLeaf(registrationsLeaf);
        configureLeaf(logBookLeaf);
        configureLeaf(detailLeaf);

        left.setPruneWhenEmpty(false);
        right.setPruneWhenEmpty(false);

        nodeRoot.setDividerPositions(0.49);
        left.setDividerPositions(0.28);
        right.setDividerPositions(0.46, 0.76);

        systemsLeaf.addDockable(
                dockable(builder, systemsLeaf,
                        new WorkbenchPanel("api-systems", "Systems", apiSystems)));

        controlsLeaf.addDockables(
                dockable(builder, controlsLeaf,
                        new WorkbenchPanel("timing-node", "TimingNode", timingNode)),
                dockable(builder, controlsLeaf,
                        new WorkbenchPanel("api-info", "API", apiInfo)),
                dockable(builder, controlsLeaf,
                        new WorkbenchPanel("registration", "Registration", registration)),
                dockable(builder, controlsLeaf,
                        new WorkbenchPanel("simulation", "Simulation", simulation)),
                dockable(builder, controlsLeaf,
                        new WorkbenchPanel("scripts", "Scripts", scripts)));

        registrationsLeaf.addDockable(
                dockable(builder, registrationsLeaf,
                        new WorkbenchPanel(
                                "registrations",
                                "Registrations [–]",
                                registrations)));
        logBookLeaf.addDockable(
                dockable(builder, logBookLeaf,
                        new WorkbenchPanel("logbook", "LogBook", logBook)));
        detailLeaf.addDockables(
                dockable(builder, detailLeaf,
                        new WorkbenchPanel("raw-data", "Raw Data", rawData)),
                dockable(builder, detailLeaf,
                        new WorkbenchPanel("events", "Events", events)));

        return nodeRoot;
    }

    private Node createCentralPerspective(
            Node centralNodes,
            Node centralLocations,
            Node centralMessages,
            Node centralRaceData) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch centralRoot =
                builder.root("central-perspective-root");
        DockContainerLeaf explorerLeaf = builder.leaf("central-explorer");
        DockContainerBranch content = builder.branch("central-content");
        DockContainerLeaf messagesLeaf = builder.leaf("central-messages");
        DockContainerLeaf raceDataLeaf = builder.leaf("central-race-data");

        centralRoot.setOrientation(Orientation.HORIZONTAL);
        content.setOrientation(Orientation.VERTICAL);
        centralRoot.addContainers(explorerLeaf, content);
        content.addContainers(messagesLeaf, raceDataLeaf);

        registerHomeOrder(centralRoot, explorerLeaf);
        registerHomeOrder(content, messagesLeaf, raceDataLeaf);

        configureLeaf(explorerLeaf);
        configureLeaf(messagesLeaf);
        configureLeaf(raceDataLeaf);

        content.setPruneWhenEmpty(false);

        centralRoot.setDividerPositions(0.27);
        content.setDividerPositions(0.67);

        explorerLeaf.addDockables(
                dockable(builder, explorerLeaf,
                        new WorkbenchPanel("central-nodes", "Nodes", centralNodes)),
                dockable(builder, explorerLeaf,
                        new WorkbenchPanel(
                                "central-locations",
                                "Locations",
                                centralLocations)));

        messagesLeaf.addDockable(
                dockable(builder, messagesLeaf,
                        new WorkbenchPanel(
                                "central-messages",
                                "Messages",
                                centralMessages)));
        raceDataLeaf.addDockable(
                dockable(builder, raceDataLeaf,
                        new WorkbenchPanel(
                                "central-race-data",
                                "RaceData",
                                centralRaceData)));

        return centralRoot;
    }

    private Node createSharedTools(
            Node deviceLog,
            Node terminal,
            Node clientLog) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch sharedRoot = builder.root("shared-tools-root");
        DockContainerLeaf sharedLeaf = builder.leaf("shared-tools");

        sharedRoot.setOrientation(Orientation.HORIZONTAL);
        sharedRoot.addContainer(sharedLeaf);
        registerHomeOrder(sharedRoot, sharedLeaf);
        configureLeaf(sharedLeaf);

        sharedLeaf.addDockables(
                dockable(builder, sharedLeaf,
                        new WorkbenchPanel(
                                "device-log",
                                "Device Log",
                                deviceLog)),
                dockable(builder, sharedLeaf,
                        new WorkbenchPanel("terminal", "Terminal", terminal)),
                dockable(builder, sharedLeaf,
                        new WorkbenchPanel(
                                "client-log",
                                "Client Log",
                                clientLog)));
        return sharedRoot;
    }

    private Bento createBento() {
        Bento bento = new Bento() {
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
        bento.stageBuilding().setApplySourceAsOwner(false);
        bento.stageBuilding().setApplyMousePosition(true);
        bento.controlsBuilding().setHeaderFactory(
                (dockable, parentPane) ->
                        new CompactBentoHeader(dockable, parentPane).withDragDrop());
        bentos.add(bento);
        return bento;
    }

    private void registerHomeOrder(
            DockContainerBranch parent,
            DockContainerLeaf... leaves) {
        List<DockContainerLeaf> order = List.of(leaves);
        homeOrdering.put(parent, order);
        for (DockContainerLeaf leaf : order) {
            homeParents.put(leaf, parent);
        }
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

        boolean registrationsDock = selected != null
                ? "registrations".equals(selected.getIdentifier())
                : leaf.getDockables().stream()
                        .anyMatch(d -> "registrations".equals(d.getIdentifier()));
        if (registrationsDock) {
            ToggleGroup scope = new ToggleGroup();
            RadioMenuItem current = new RadioMenuItem("Current open location");
            RadioMenuItem all = new RadioMenuItem("All locations");
            current.setToggleGroup(scope);
            all.setToggleGroup(scope);
            current.setSelected(!allRegistrations.getAsBoolean());
            all.setSelected(allRegistrations.getAsBoolean());
            current.setOnAction(event -> setAllRegistrations.accept(false));
            all.setOnAction(event -> setAllRegistrations.accept(true));
            MenuItem timezone = new MenuItem(
                    "Times shown in " + java.time.ZoneId.systemDefault().getId());
            timezone.setDisable(true);
            menu.getItems().addAll(
                    current, all, timezone, new SeparatorMenuItem());
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
        if (panel != null) {
            String title = "LogBook (" + count + ")";
            if (!title.equals(panel.getTitle())) {
                panel.setTitle(title);
            }
        }
    }

    void setRegistrationScope(String scope) {
        Dockable panel = dockables.get("registrations");
        if (panel != null) {
            String title = "Registrations [" + scope + "]";
            if (!title.equals(panel.getTitle())) {
                panel.setTitle(title);
            }
        }
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

    /** Restores one detached or rearranged panel to its original perspective/tool area. */
    void dockBack(String id) {
        Dockable panel = dockables.get(id);
        DockContainerLeaf home = homeLeaves.get(id);
        if (panel == null || home == null || panel.getContainer() == home) {
            return;
        }
        DockContainerLeaf current = panel.getContainer();
        if (current != null) {
            current.removeDockable(panel);
        }
        restoreHomeArea(home);
        home.addDockable(panel);
        home.selectDockable(panel);
    }

    /** A pruned leaf must be reinserted at its normal position before re-pinning. */
    private void restoreHomeArea(DockContainerLeaf home) {
        DockContainerBranch parent = homeParents.get(home);
        if (parent == null || home.getParentContainer() == parent) {
            return;
        }
        List<DockContainerLeaf> order = homeOrdering.get(parent);
        int targetIndex = order.indexOf(home);
        int insertAt = 0;
        for (int i = 0; i < targetIndex; i++) {
            if (order.get(i).getParentContainer() == parent) {
                insertAt++;
            }
        }
        parent.addContainer(insertAt, home);
    }

    /** Release old dockables before rebuilding the canonical workbench tree. */
    void release() {
        for (Dockable dockable : dockables.values()) {
            dockable.setNode(null);
            DockContainerLeaf current = dockable.getContainer();
            if (current != null) {
                current.removeDockable(dockable);
            }
        }
    }

    private Dockable dockable(
            DockBuilding builder,
            DockContainerLeaf home,
            WorkbenchPanel panel) {
        Dockable dockable = builder.dockable(panel.id());
        dockable.setTitle(panel.title());
        dockable.setClosable(false);
        dockable.setNode(panel.content());
        dockable.setDragGroupMask(WORKBENCH_DRAG_GROUP);
        dockables.put(panel.id(), dockable);
        homeLeaves.put(panel.id(), home);
        return dockable;
    }
}
