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
 * <p>Node events only wake the control task. A control run reads current node
 * statuses and publishes desired inventory to shared AntennaSettings.</p>
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
     * <p>This callback validates node ownership and wakes the coalesced control
     * task. The later control run reads CURRENT published state; this callback
     * never waits on another execution lane.</p>
     */
    public void onTimingNodeStatusChanged(TimingNode node, Status status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        boolean found = false;
        for (TimingNode candidate : nodes) {
            if (candidate == node) { found = true; break; }
        }
        if (!found) { throw new IllegalArgumentException("foreign TimingNode"); }
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
            if (status.state() == State.OPEN) { required = true; }
        }
        if (antennaSettings != null) {
            final boolean enabled = required;
            antennaSettings.update(edit -> edit.inventoryEnabled(enabled));
        }
        return TaskStep.done();
    }

    private void onControlTaskFailure(Throwable failure) {
        LOG.error("TimingSystem Conductor control task failed", failure);
    }
}
