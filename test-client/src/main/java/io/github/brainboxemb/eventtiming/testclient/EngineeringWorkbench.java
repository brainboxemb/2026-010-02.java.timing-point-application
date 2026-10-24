package io.github.brainboxemb.eventtiming.testclient;

import javafx.geometry.Orientation;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.WindowEvent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import software.coley.bentofx.Bento;
import software.coley.bentofx.building.DockBuilding;
import software.coley.bentofx.control.DragDropStage;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerBranch;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.bentofx.layout.container.DockContainerRootBranch;

/**
 * BentoFX composition for the SI-02 Engineering Desktop Client.
 *
 * <p>The left side owns engineering navigation, controls and always-visible diagnostics.
 * The right side is reserved for detachable viewer workspaces such as Node A and
 * Central A. Viewer workspaces are ordinary Bento dockables so later Node B / Central B
 * viewers can share the same right-side tab area.</p>
 */
final class EngineeringWorkbench {
    private static final int WORKBENCH_DRAG_GROUP = 1;

    private static final double DETACHED_MIN_WIDTH = 480.0;
    private static final double DETACHED_MAX_WIDTH = 960.0;
    private static final double DETACHED_MIN_HEIGHT = 320.0;
    private static final double DETACHED_MAX_HEIGHT = 640.0;

    private final BooleanSupplier allRegistrations;
    private final Consumer<Boolean> setAllRegistrations;

    private final List<Bento> bentos = new ArrayList<>();
    private final Map<String, Dockable> dockables = new LinkedHashMap<>();
    private final Map<String, DockContainerLeaf> homeLeaves = new LinkedHashMap<>();
    private final Map<DockContainerLeaf, DockContainerBranch> homeParents =
            new LinkedHashMap<>();
    private final Map<DockContainerBranch, List<DockContainerLeaf>> homeOrdering =
            new LinkedHashMap<>();

    private final DockContainerRootBranch root;
    private final Dockable nodeViewer;
    private final Dockable centralViewer;

    EngineeringWorkbench(
            Node apiSystems,
            Node centralNodes,
            Node centralLocations,
            Node centralMessages,
            Node centralLogBook,
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
            Node http,
            BooleanSupplier allRegistrations,
            Consumer<Boolean> setAllRegistrations) {
        this.allRegistrations = java.util.Objects.requireNonNull(allRegistrations);
        this.setAllRegistrations = java.util.Objects.requireNonNull(setAllRegistrations);

        Node nodeControls = createNodeControls(
                timingNode,
                apiInfo,
                registration,
                simulation,
                scripts);
        Node centralControls = createCentralControls(centralRaceData);
        Node centralExplorer =
                createCentralExplorer(
                        centralNodes,
                        centralLocations);
        Node nodeViewerContent = createNodeViewer(
                registrations,
                logBook,
                rawData,
                events,
                http);
        Node centralViewerContent =
                createCentralViewer(
                        centralMessages,
                        centralLogBook);

        Bento outerBento = createBento();
        DockBuilding builder = outerBento.dockBuilding();
        root = builder.root("engineering-root");

        DockContainerBranch leftColumn = builder.branch("left-column");
        DockContainerBranch leftTop = builder.branch("left-top");
        DockContainerBranch explorerBranch = builder.branch("explorer");
        DockContainerLeaf systemLeaf = builder.leaf("explorer-system");
        DockContainerLeaf centralLeaf = builder.leaf("explorer-central");
        DockContainerLeaf controlsLeaf = builder.leaf("controls");
        DockContainerLeaf deviceLogLeaf = builder.leaf("device-log");
        DockContainerLeaf terminalLeaf = builder.leaf("terminal");
        DockContainerLeaf clientLogLeaf = builder.leaf("client-log");
        DockContainerBranch rightColumn = builder.branch("viewer-column");
        DockContainerLeaf viewerLeaf = builder.leaf("viewers");

        root.setOrientation(Orientation.HORIZONTAL);
        leftColumn.setOrientation(Orientation.VERTICAL);
        leftTop.setOrientation(Orientation.HORIZONTAL);
        explorerBranch.setOrientation(Orientation.VERTICAL);
        rightColumn.setOrientation(Orientation.HORIZONTAL);

        root.addContainers(leftColumn, rightColumn);
        leftColumn.addContainers(
                leftTop,
                deviceLogLeaf,
                terminalLeaf,
                clientLogLeaf);
        leftTop.addContainers(explorerBranch, controlsLeaf);
        explorerBranch.addContainers(systemLeaf, centralLeaf);
        rightColumn.addContainers(viewerLeaf);

        registerHomeOrder(
                explorerBranch,
                systemLeaf,
                centralLeaf);
        registerHomeOrder(
                leftColumn,
                deviceLogLeaf,
                terminalLeaf,
                clientLogLeaf);
        registerHomeOrder(rightColumn, viewerLeaf);

        configureLeaf(systemLeaf);
        configureLeaf(centralLeaf);
        configureLeaf(controlsLeaf);
        configureLeaf(deviceLogLeaf);
        configureLeaf(terminalLeaf);
        configureLeaf(clientLogLeaf);
        configureLeaf(viewerLeaf);

        leftTop.setPruneWhenEmpty(false);
        explorerBranch.setPruneWhenEmpty(false);
        controlsLeaf.setPruneWhenEmpty(false);
        leftColumn.setPruneWhenEmpty(false);
        rightColumn.setPruneWhenEmpty(false);

        // Restore the useful pre-perspective proportions.
        root.setDividerPositions(0.49);
        leftColumn.setDividerPositions(0.43, 0.64, 0.82);
        leftTop.setDividerPositions(0.28);
        explorerBranch.setDividerPositions(0.48);

        systemLeaf.addDockable(
                dockable(builder, systemLeaf,
                        new WorkbenchPanel(
                                "api-systems",
                                "System",
                                apiSystems)));

        centralLeaf.addDockable(
                dockable(builder, centralLeaf,
                        new WorkbenchPanel(
                                "central-explorer",
                                "Central",
                                centralExplorer)));

        controlsLeaf.addDockables(
                dockable(builder, controlsLeaf,
                        new WorkbenchPanel(
                                "node-controls",
                                "Node Controls",
                                nodeControls)),
                dockable(builder, controlsLeaf,
                        new WorkbenchPanel(
                                "central-controls",
                                "Central Controls",
                                centralControls)));

        deviceLogLeaf.addDockable(
                dockable(builder, deviceLogLeaf,
                        new WorkbenchPanel(
                                "device-log",
                                "Device Log",
                                deviceLog)));
        terminalLeaf.addDockable(
                dockable(builder, terminalLeaf,
                        new WorkbenchPanel(
                                "terminal",
                                "Terminal",
                                terminal)));
        clientLogLeaf.addDockable(
                dockable(builder, clientLogLeaf,
                        new WorkbenchPanel(
                                "client-log",
                                "Client Log",
                                clientLog)));

        nodeViewer = dockable(
                builder,
                viewerLeaf,
                new WorkbenchPanel(
                        "node-viewer",
                        "Node",
                        nodeViewerContent));
        centralViewer = dockable(
                builder,
                viewerLeaf,
                new WorkbenchPanel(
                        "central-viewer",
                        "Central",
                        centralViewerContent));
        viewerLeaf.addDockables(nodeViewer, centralViewer);
    }

    Node root() {
        return root;
    }

    void setPerspectiveNodeId(String nodeId) {
        nodeViewer.setTitle(perspectiveTitle("Node", nodeId));
        centralViewer.setTitle(perspectiveTitle("Central", nodeId));
    }

    static String perspectiveTitle(String perspective, String nodeId) {
        if (perspective == null || perspective.isBlank()) {
            throw new IllegalArgumentException("perspective must not be blank");
        }
        return nodeId == null || nodeId.isBlank()
                ? perspective
                : perspective + " " + nodeId.trim();
    }

    private Node createCentralExplorer(
            Node centralNodes,
            Node centralLocations) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch explorerRoot =
                builder.root("central-explorer-root");
        DockContainerLeaf tabs =
                builder.leaf("central-explorer-tabs");

        explorerRoot.setOrientation(Orientation.HORIZONTAL);
        explorerRoot.addContainers(tabs);
        registerHomeOrder(explorerRoot, tabs);
        configureLeaf(tabs);

        tabs.addDockables(
                dockable(builder, tabs,
                        new WorkbenchPanel(
                                "central-nodes",
                                "Nodes",
                                centralNodes)),
                dockable(builder, tabs,
                        new WorkbenchPanel(
                                "central-locations",
                                "Locations",
                                centralLocations)));
        return explorerRoot;
    }

    private Node createNodeControls(
            Node timingNode,
            Node apiInfo,
            Node registration,
            Node simulation,
            Node scripts) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch controlsRoot = builder.root("node-controls-root");
        DockContainerLeaf controls = builder.leaf("node-controls-tabs");

        controlsRoot.setOrientation(Orientation.HORIZONTAL);
        controlsRoot.addContainers(controls);
        registerHomeOrder(controlsRoot, controls);
        configureLeaf(controls);

        controls.addDockables(
                dockable(builder, controls,
                        new WorkbenchPanel(
                                "timing-node",
                                "TimingNode",
                                timingNode)),
                dockable(builder, controls,
                        new WorkbenchPanel(
                                "api-info",
                                "API",
                                apiInfo)),
                dockable(builder, controls,
                        new WorkbenchPanel(
                                "registration",
                                "Registration",
                                registration)),
                dockable(builder, controls,
                        new WorkbenchPanel(
                                "simulation",
                                "Simulation",
                                simulation)),
                dockable(builder, controls,
                        new WorkbenchPanel(
                                "scripts",
                                "Scripts",
                                scripts)));

        return controlsRoot;
    }

    private Node createCentralControls(Node centralRaceData) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch controlsRoot =
                builder.root("central-controls-root");
        DockContainerLeaf controls = builder.leaf("central-controls-tabs");

        controlsRoot.setOrientation(Orientation.HORIZONTAL);
        controlsRoot.addContainers(controls);
        registerHomeOrder(controlsRoot, controls);
        configureLeaf(controls);

        controls.addDockable(
                dockable(builder, controls,
                        new WorkbenchPanel(
                                "central-race-data",
                                "RaceData",
                                centralRaceData)));
        return controlsRoot;
    }

    private Node createNodeViewer(
            Node registrations,
            Node logBook,
            Node rawData,
            Node events,
            Node http) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch viewerRoot = builder.root("node-viewer-root");
        DockContainerBranch content = builder.branch("node-viewer-content");
        DockContainerLeaf registrationsLeaf =
                builder.leaf("node-registrations");
        DockContainerLeaf logBookLeaf = builder.leaf("node-logbook");
        DockContainerLeaf detailLeaf = builder.leaf("node-detail");

        viewerRoot.setOrientation(Orientation.VERTICAL);
        content.setOrientation(Orientation.VERTICAL);
        viewerRoot.addContainers(content);
        content.addContainers(
                registrationsLeaf,
                logBookLeaf,
                detailLeaf);

        registerHomeOrder(
                content,
                registrationsLeaf,
                logBookLeaf,
                detailLeaf);

        configureLeaf(registrationsLeaf);
        configureLeaf(logBookLeaf);
        configureLeaf(detailLeaf);
        content.setPruneWhenEmpty(false);
        content.setDividerPositions(0.46, 0.76);

        registrationsLeaf.addDockable(
                dockable(builder, registrationsLeaf,
                        new WorkbenchPanel(
                                "registrations",
                                "Registrations [–]",
                                registrations)));
        logBookLeaf.addDockable(
                dockable(builder, logBookLeaf,
                        new WorkbenchPanel(
                                "logbook",
                                "LogBook",
                                logBook)));
        detailLeaf.addDockables(
                dockable(builder, detailLeaf,
                        new WorkbenchPanel(
                                "raw-data",
                                "Raw Data",
                                rawData)),
                dockable(builder, detailLeaf,
                        new WorkbenchPanel(
                                "events",
                                "Events",
                                events)),
                dockable(builder, detailLeaf,
                        new WorkbenchPanel(
                                "http",
                                "HTTP",
                                http)));

        return viewerRoot;
    }

    private Node createCentralViewer(
            Node centralMessages,
            Node centralLogBook) {
        Bento bento = createBento();
        DockBuilding builder = bento.dockBuilding();
        DockContainerRootBranch viewerRoot =
                builder.root("central-viewer-root");
        DockContainerLeaf messagesLeaf = builder.leaf("central-messages");

        viewerRoot.setOrientation(Orientation.HORIZONTAL);
        viewerRoot.addContainers(messagesLeaf);
        registerHomeOrder(viewerRoot, messagesLeaf);
        configureLeaf(messagesLeaf);

        messagesLeaf.addDockables(
                dockable(builder, messagesLeaf,
                        new WorkbenchPanel(
                                "central-messages",
                                "Messages",
                                centralMessages)),
                dockable(builder, messagesLeaf,
                        new WorkbenchPanel(
                                "central-logbook",
                                "LogBook",
                                centralLogBook)));
        return viewerRoot;
    }

    private Bento createBento() {
        Bento bento = new Bento() {
            @Override
            protected DockBuilding newDockBuilding() {
                return new DockBuilding(this) {
                    @Override
                    public DockContainerLeaf leaf(String identifier) {
                        DockContainerLeaf leaf = super.leaf(identifier);
                        leaf.setMenuFactory(
                                EngineeringWorkbench.this::buildDockMenu);
                        return leaf;
                    }
                };
            }
        };
        bento.stageBuilding().setApplySourceAsOwner(false);
        bento.stageBuilding().setApplyMousePosition(true);
        bento.stageBuilding().setStageFactory(sourceStage -> {
            DragDropStage stage = new DragDropStage(true);
            stage.addEventHandler(
                    WindowEvent.WINDOW_SHOWN,
                    event -> clampDetachedStage(stage));
            return stage;
        });
        bento.stageBuilding().setSceneFactory(
                (sourceScene, content, width, height) ->
                        new Scene(
                                content,
                                detachedWidth(width),
                                detachedHeight(height)));
        bento.controlsBuilding().setHeaderFactory(
                (dockable, parentPane) ->
                        new CompactBentoHeader(dockable, parentPane)
                                .withDragDrop());
        bentos.add(bento);
        return bento;
    }

    private static void clampDetachedStage(DragDropStage stage) {
        Rectangle2D bounds = detachedScreenBounds(stage);

        double width = detachedExtent(
                stage.getWidth(),
                DETACHED_MIN_WIDTH,
                DETACHED_MAX_WIDTH,
                bounds.getWidth());
        double height = detachedExtent(
                stage.getHeight(),
                DETACHED_MIN_HEIGHT,
                DETACHED_MAX_HEIGHT,
                bounds.getHeight());

        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(detachedPosition(
                stage.getX(),
                width,
                bounds.getMinX(),
                bounds.getMaxX()));
        stage.setY(detachedPosition(
                stage.getY(),
                height,
                bounds.getMinY(),
                bounds.getMaxY()));
    }

    private static Rectangle2D detachedScreenBounds(DragDropStage stage) {
        List<Screen> screens = Screen.getScreensForRectangle(
                stage.getX(),
                stage.getY(),
                1.0,
                1.0);
        return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0))
                .getVisualBounds();
    }

    static double detachedExtent(
            double actual,
            double minimum,
            double maximum,
            double available) {
        double upper = Math.max(1.0, Math.min(maximum, available));
        double lower = Math.min(minimum, upper);
        return Math.max(lower, Math.min(upper, actual));
    }

    static double detachedPosition(
            double position,
            double extent,
            double minimum,
            double maximum) {
        double latest = Math.max(minimum, maximum - extent);
        if (!Double.isFinite(position)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(latest, position));
    }

    static double detachedWidth(double sourceWidth) {
        return Math.max(
                DETACHED_MIN_WIDTH,
                Math.min(DETACHED_MAX_WIDTH, sourceWidth));
    }

    static double detachedHeight(double sourceHeight) {
        return Math.max(
                DETACHED_MIN_HEIGHT,
                Math.min(DETACHED_MAX_HEIGHT, sourceHeight));
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
            DockContainerLeaf home =
                    homeLeaves.get(selected.getIdentifier());
            if (home != null && home != selected.getContainer()) {
                MenuItem dockBack =
                        new MenuItem("Dock selected tab back");
                dockBack.setOnAction(
                        event -> dockBack(selected.getIdentifier()));
                menu.getItems().addAll(
                        dockBack,
                        new SeparatorMenuItem());
            }
        }

        boolean registrationsDock = selected != null
                ? "registrations".equals(selected.getIdentifier())
                : leaf.getDockables().stream()
                        .anyMatch(d ->
                                "registrations"
                                        .equals(d.getIdentifier()));
        if (registrationsDock) {
            ToggleGroup scope = new ToggleGroup();
            RadioMenuItem current =
                    new RadioMenuItem("Current open location");
            RadioMenuItem all =
                    new RadioMenuItem("All locations");
            current.setToggleGroup(scope);
            all.setToggleGroup(scope);
            current.setSelected(!allRegistrations.getAsBoolean());
            all.setSelected(allRegistrations.getAsBoolean());
            current.setOnAction(
                    event -> setAllRegistrations.accept(false));
            all.setOnAction(
                    event -> setAllRegistrations.accept(true));
            MenuItem timezone = new MenuItem(
                    "Times shown in "
                            + java.time.ZoneId.systemDefault().getId());
            timezone.setDisable(true);
            menu.getItems().addAll(
                    current,
                    all,
                    timezone,
                    new SeparatorMenuItem());
        }

        Menu tabPosition = new Menu("Tab position");
        ToggleGroup positionGroup = new ToggleGroup();
        for (Side side : Side.values()) {
            RadioMenuItem item =
                    new RadioMenuItem(switch (side) {
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

    /** Restores one detached or rearranged panel to its original dock area. */
    void dockBack(String id) {
        Dockable panel = dockables.get(id);
        DockContainerLeaf home = homeLeaves.get(id);
        if (panel == null
                || home == null
                || panel.getContainer() == home) {
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
