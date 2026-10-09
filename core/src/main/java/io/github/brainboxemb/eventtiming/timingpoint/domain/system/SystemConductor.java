package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.DerivedProperty;
import io.github.brainboxemb.eventtiming.timingpoint.infra.property.SourceProperty;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTaskController;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates one TimingSystem.
 *
 * <p>The SystemConductor owns TimingNode lifecycle and the system-wide inventory
 * decision. SourceProperty and DerivedProperty only hold current state; all
 * scheduling and coalescing remain in this Conductor's CooperativeTaskController.</p>
 *
 * <p>Every control run refreshes current state from each TimingNode's CURRENT
 * published Status before deriving inventory intent. TimingNode status events
 * only wake this control task; they do not mutate SourceProperties directly.</p>
 */
public final class SystemConductor extends AbstractConductor implements CooperativeTask {
    private static final Logger LOG = LoggerFactory.getLogger(SystemConductor.class);

    private final PropertyRegistry properties = new PropertyRegistry();
    private final AntennaManager antennaManager;
    private final CooperativeTaskController taskController;
    private final DerivedProperty<Boolean> inventoryRequired;

    public SystemConductor(
            TimingNodeList timingNodes,
            AntennaManager antennaManager,
            SerialExecutor coordinationLane) {
        super(coordinationLane);

        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException("timingNodes must not be empty");
        }

        this.antennaManager = antennaManager;
        taskController = new CooperativeTaskController(
                new SerialTaskRunner(coordinationLane()),
                this,
                this::onControlTaskFailure);

        for (TimingNode node : timingNodes) {
            properties.register(new SourceProperty<TimingNode, State>(node));
            registerComponent(
                    "TimingNode " + node.timingNodeId().value(),
                    node::activate,
                    node::deactivate);
        }

        inventoryRequired = new DerivedProperty<Boolean>(this::deriveInventoryRequired);
    }

    /**
     * Accepts one authoritative post-change TimingNode Status.
     *
     * <p>This callback validates node ownership and wakes the coalesced control
     * task. The later control run reads CURRENT published state; this callback
     * never waits on another execution lane.</p>
     */
    public void onTimingNodeStatusChanged(TimingNode node, Status status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        requireNodeProperty(node);
        taskController.wake();
    }

    SourceProperty<TimingNode, State> nodeStateProperty(TimingNode node) {
        return requireNodeProperty(node);
    }

    @Override
    protected void onActivated() {
        taskController.clearPendingWake();
        taskController.wake();
    }

    @Override
    public TaskStep runStep() {
        refreshSourceProperties();
        inventoryRequired.recalculate();

        if (antennaManager != null && !antennaManager.setInventoryEnabled(inventoryRequired.currentValue())) {
            LOG.warn(
                    "AntennaManager rejected inventory enabled={} managerState={}",
                    inventoryRequired.currentValue(),
                    antennaManager.state());
        }

        return TaskStep.done();
    }

    private void refreshSourceProperties() {
        for (SourceProperty<TimingNode, State> property : properties) {
            Status status =
                    property.source().query(
                            TimingNodeQueries.status(),
                            ReadConsistency.CURRENT);
            property.update(status.state());
        }
    }

    private Boolean deriveInventoryRequired() {
        for (SourceProperty<TimingNode, State> property : properties) {
            if (!property.initialized()) {
                throw new IllegalStateException("TimingNode source property is not initialized");
            }
            if (property.currentValue() == State.OPEN) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    private SourceProperty<TimingNode, State> requireNodeProperty(TimingNode node) {
        SourceProperty<TimingNode, State> property = properties.get(node);
        if (property == null) {
            throw new IllegalArgumentException("TimingNode does not belong to this TimingSystem");
        }
        return property;
    }

    private void onControlTaskFailure(Throwable failure) {
        LOG.error("TimingSystem Conductor control task failed", failure);
    }
}
