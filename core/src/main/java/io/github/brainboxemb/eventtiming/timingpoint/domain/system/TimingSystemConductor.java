package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSettings;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTaskController;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.taskrunners.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates one TimingSystem.
 *
 * <p>The conductor owns its TimingNodes, their lifecycle and the node-status
 * subscriptions used for system coordination. These fixed relationships are
 * established during construction, before activation starts execution.</p>
 *
 * <p>A status event only wakes the control task. The subsequent run reads
 * every TimingNode's CURRENT status and publishes the desired inventory state
 * through the shared AntennaSettings. It does not retain a second copy of
 * node state or invoke AntennaManager directly.</p>
 */
public final class TimingSystemConductor extends AbstractConductor implements YieldingTask {
    private static final Logger LOG = LoggerFactory.getLogger(TimingSystemConductor.class);

    private final TimingNodeList nodes;
    private final AntennaSettings antennaSettings;
    private final YieldingTaskController taskController;

    public TimingSystemConductor(
            TimingNodeList timingNodes,
            AntennaSettings antennaSettings,
            SerialLane coordinationLane) {

        super(coordinationLane);

        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException("timingNodes must not be empty");
        }

        this.nodes = timingNodes;
        this.antennaSettings = antennaSettings;
        taskController = new YieldingTaskController(
                new SerialTaskRunner(coordinationLane()),
                this,
                this::onControlTaskFailure);

        for (TimingNode node : timingNodes) {
            registerComponent(
                    "TimingNode " + node.timingNodeId().value(),
                    node::activate,
                    node::deactivate);
            node.logBook().statusChangedEvent().subscribe(
                    status -> onTimingNodeStatusChanged(node, status));
        }
    }

    /** Accepts one authoritative post-change TimingNode Status. */
    private void onTimingNodeStatusChanged(TimingNode node, Status status) {

        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }

        if (!containsNode(node)) {
            throw new IllegalArgumentException("TimingNode does not belong to this TimingSystem");
        }

        /*
         * A TimingNode may finish its first recovery while this Conductor is
         * still activating its children. onActivated() always requests the first
         * reconciliation, so an event arriving before this lane is RUNNING does
         * not need to be retained separately.
         */
        if (coordinationLane().state() == SerialLane.State.RUNNING) {
            taskController.wake();
        }
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
            final Status status;
            try {
                status = node.logBook().process(
                        LogBookQueries.status(),
                        ReadConsistency.CURRENT);
            } catch (OperationException ex) {

                if (ex.reason() != OperationException.Reason.UNAVAILABLE) {
                    throw ex;
                }

                /*
                 * Activation starts node recovery asynchronously. Do not wait
                 * here and do not publish a partial system decision. The
                 * node's first Status publication wakes a later reconciliation.
                 */
                return TaskStep.done();
            }

            if (status.state() == State.OPEN) {
                required = true;
            }
        }
        if (antennaSettings != null) {
            final boolean inventoryRequired = required;
            Consumer<AntennaSettings.Edit> settingsUpdate =
                    edit -> edit.inventoryEnabled(inventoryRequired);

            antennaSettings.update(settingsUpdate);
        }
        return TaskStep.done();
    }

    private boolean containsNode(TimingNode node) {

        return nodes.contains(node);
    }

    private void onControlTaskFailure(Throwable failure) {

        LOG.error("TimingSystem Conductor control task failed", failure);
    }
}
