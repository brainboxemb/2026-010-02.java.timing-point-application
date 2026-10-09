package io.github.brainboxemb.eventtiming.testclient;

import javafx.geometry.Orientation;
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

    EngineeringWorkbench(
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

        root.addContainers(leftColumn, rightColumn);
        leftColumn.addContainers(controls, deviceLogLeaf, terminalLeaf, clientLogLeaf);
        rightColumn.addContainers(registrationsLeaf, logBookLeaf, detailLeaf);

        configureLeaf(controls);
        configureLeaf(deviceLogLeaf);
        configureLeaf(terminalLeaf);
        configureLeaf(clientLogLeaf);
        configureLeaf(registrationsLeaf);
        configureLeaf(logBookLeaf);
        configureLeaf(detailLeaf);

        leftColumn.setPruneWhenEmpty(false);
        rightColumn.setPruneWhenEmpty(false);

        root.setDividerPositions(0.50);
        leftColumn.setDividerPositions(0.46, 0.64, 0.82);
        rightColumn.setDividerPositions(0.46, 0.76);

        controls.addDockables(
                dockable(builder, new WorkbenchPanel("timing-node", "TimingNode", timingNode)),
                dockable(builder, new WorkbenchPanel("registration", "Registration", registration)),
                dockable(builder, new WorkbenchPanel("simulation", "Simulation", simulation)));

        deviceLogLeaf.addDockable(
                dockable(builder, new WorkbenchPanel("device-log", "Device Log", deviceLog)));
        terminalLeaf.addDockable(
                dockable(builder, new WorkbenchPanel("terminal", "Terminal", terminal)));
        clientLogLeaf.addDockable(
                dockable(builder, new WorkbenchPanel("client-log", "Client Log", clientLog)));

        registrationsLeaf.addDockable(
                dockable(builder, new WorkbenchPanel("registrations", "Registrations", registrations)));
        logBookLeaf.addDockable(
                dockable(builder, new WorkbenchPanel("logbook", "LogBook", logBook)));
        detailLeaf.addDockables(
                dockable(builder, new WorkbenchPanel("raw-data", "Raw Data", rawData)),
                dockable(builder, new WorkbenchPanel("events", "Events", events)));
    }

    DockContainerRootBranch root() {
        return root;
    }

    private static void configureLeaf(DockContainerLeaf leaf) {
        leaf.setSide(Side.TOP);
    }

    private static Dockable dockable(DockBuilding builder, WorkbenchPanel panel) {
        Dockable dockable = builder.dockable(panel.id());
        dockable.setTitle(panel.title());
        dockable.setNode(panel.content());
        dockable.setDragGroupMask(WORKBENCH_DRAG_GROUP);
        return dockable;
    }
}
