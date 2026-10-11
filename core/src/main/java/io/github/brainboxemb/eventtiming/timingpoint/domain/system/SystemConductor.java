package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSettings;
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
 * <p>Runtime composition wires each TimingNode's statusChangedEvent() to
 * {@link #onTimingNodeStatusChanged(TimingNode, Status)} before activation.
 * This class deliberately does not subscribe to its own dependencies in its
 * constructor: event wiring belongs to the composition root and is visible
 * alongside the rest of the application graph.</p>
 *
 * <p>A status event only wakes the control task. The subsequent run reads
 * every TimingNode's CURRENT status and publishes the desired inventory state
 * through the shared AntennaSettings. It does not retain a second copy of
 * node state or invoke AntennaManager directly.</p>
 */
public final class SystemConductor extends AbstractConductor implements CooperativeTask {
    private static final Logger LOG = LoggerFactory.getLogger(SystemConductor.class);

    private final TimingNodeList nodes;
    private final AntennaSettings antennaSettings;
    private final CooperativeTaskController taskController;

    public SystemConductor(
            TimingNodeList timingNodes,
            AntennaSettings antennaSettings,
            SerialExecutor coordinationLane) {
        super(coordinationLane);

        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException("timingNodes must not be empty");
        }

        this.nodes = timingNodes.copy();
        this.antennaSettings = antennaSettings;
        taskController = new CooperativeTaskController(
                new SerialTaskRunner(coordinationLane()),
                this,
                this::onControlTaskFailure);

        for (TimingNode node : timingNodes) {
            registerComponent(
                    "TimingNode " + node.timingNodeId().value(),
                    node::activate,
                    node::deactivate);
        }
    }

    /**
     * Accepts one authoritative post-change TimingNode Status.
     *
     * <p>The subscription is installed by runtime composition, not by this
     * Conductor. This handler validates node ownership and only wakes the
     * coalesced control task. The later run reads CURRENT published state;
     * the event payload is not cached or treated as a control command.</p>
     */
    public void onTimingNodeStatusChanged(TimingNode node, Status status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        if (!containsNode(node)) {
            throw new IllegalArgumentException("TimingNode does not belong to this TimingSystem");
        }
        taskController.wake();
    }

    @Override
    protected void onActivated() {
        taskController.clearPendingWake();
        taskController.wake();
    }

    @Override
    public TaskStep runStep() {
        boolean required = false;
        for (TimingNode node : nodes) {
            Status status = node.query(TimingNodeQueries.status(), ReadConsistency.CURRENT);
            if (status.state() == State.OPEN) {
                required = true;
            }
        }
        if (antennaSettings != null) {
            // Capture the aggregate decision for this run. update() executes
            // the lambda synchronously as one settings transaction: the editor
            // sets the desired value, which is published only after the lambda
            // completes. An unchanged value publishes nothing. Hardware
            // reconciliation remains asynchronous in AntennaManager.
            final boolean enabled = required;
            antennaSettings.update(edit -> edit.inventoryEnabled(enabled));
        }
        return TaskStep.done();
    }

    private boolean containsNode(TimingNode node) {
        for (TimingNode candidate : nodes) {
            if (candidate == node) {
                return true;
            }
        }
        return false;
    }

    private void onControlTaskFailure(Throwable failure) {
        LOG.error("TimingSystem Conductor control task failed", failure);
    }
}
