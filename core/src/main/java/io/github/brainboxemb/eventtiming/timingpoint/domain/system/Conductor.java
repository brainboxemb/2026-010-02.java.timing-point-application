package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.InventoryControl;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTaskController;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates one TimingSystem's operational behavior.
 *
 * <p>Each TimingNode remains the authority for its own state. Changes only
 * trigger a refresh on the system coordination lane. This coordinator
 * determines one inventory request for the complete AntennaManager: inventory
 * is needed while at least one TimingNode is OPEN.</p>
 *
 * <p>TagObservation routing is configured separately by Runtime. Individual
 * antenna control, power, initialization and multiplexing do not belong here.</p>
 */
public final class Conductor extends AbstractConductor
        implements CooperativeTask {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final List<TimingNode> timingNodes;
    private final List<TimingNodeStateProperty> stateProperties;
    private final Map<TimingNode, TimingNodeStateProperty> propertiesByNode;
    private final InventoryControl inventoryControl;
    private final CooperativeTaskController taskController;

    /**
     * @param timingNodes one or more nodes belonging to this TimingSystem
     * @param inventoryControl optional manager-wide inventory and lifecycle port
     * @param coordinationLane Runtime-owned logical serial lane for this system
     */
    public Conductor(
            List<TimingNode> timingNodes,
            InventoryControl inventoryControl,
            SerialExecutor coordinationLane) {
        super(coordinationLane);
        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException("timingNodes must not be empty");
        }

        this.inventoryControl = inventoryControl;
        this.timingNodes = Collections.unmodifiableList(
                new ArrayList<TimingNode>(timingNodes));
        this.stateProperties = new ArrayList<TimingNodeStateProperty>();
        this.propertiesByNode =
                new IdentityHashMap<TimingNode, TimingNodeStateProperty>();
        this.taskController = new CooperativeTaskController(
                new SerialTaskRunner(coordinationLane()),
                this,
                this::onControlTaskFailure);

        for (TimingNode node : this.timingNodes) {
            if (node == null || propertiesByNode.containsKey(node)) {
                throw new IllegalArgumentException(
                        "timingNodes must contain distinct non-null nodes");
            }

            TimingNodeStateProperty property =
                    new TimingNodeStateProperty(node, coordinationLane());
            stateProperties.add(property);
            propertiesByNode.put(node, property);
            property.changedEvent().subscribe(
                    ignored -> taskController.wake());

            registerComponent(
                    "TimingNode " + node.timingNodeId().value(),
                    node::activate,
                    node::deactivate);
        }

        if (inventoryControl != null) {
            registerComponent(
                    "AntennaManager",
                    inventoryControl::activate,
                    inventoryControl::deactivate);
        }
    }

    /** Signals that one node's authoritative state may have changed. */
    public boolean signalTimingNodeStateChanged(TimingNode node) {
        TimingNodeStateProperty property = propertiesByNode.get(node);
        if (property == null) {
            throw new IllegalArgumentException(
                    "TimingNode does not belong to this TimingSystem");
        }
        return property.signalChanged();
    }

    // Package-local inspection of the authoritative tracked value for tests.
    TimingNodeStateProperty nodeStateProperty(TimingNode node) {
        TimingNodeStateProperty property = propertiesByNode.get(node);
        if (property == null) {
            throw new IllegalArgumentException("Unknown TimingNode");
        }
        return property;
    }

    @Override
    protected void onActivated() {
        // Component activation precedes the first authoritative state read.
        for (TimingNodeStateProperty property : stateProperties) {
            property.initialize();
        }
        // Reconcile once even when no status-change event was emitted.
        if (inventoryControl != null) {
            taskController.wake();
        }
    }

    /** One coalesced pass using the latest tracked node states. */
    @Override
    public TaskStep runStep() {
        if (inventoryControl == null) {
            return TaskStep.done();
        }

        boolean anyOpen = false;
        for (TimingNodeStateProperty property : stateProperties) {
            if (!property.initialized()) {
                return TaskStep.done();
            }
            if (property.currentValue() == TimingNodeTypes.State.OPEN) {
                anyOpen = true;
            }
        }

        if (!inventoryControl.setInventoryEnabled(anyOpen)) {
            LOG.warn("AntennaManager rejected inventory enabled={}", anyOpen);
        }
        return TaskStep.done();
    }

    private void onControlTaskFailure(Throwable failure) {
        LOG.error("Conductor control task failed", failure);
    }
}
