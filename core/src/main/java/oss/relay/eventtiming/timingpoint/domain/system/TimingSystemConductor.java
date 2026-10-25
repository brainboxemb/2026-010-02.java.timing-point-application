package oss.relay.eventtiming.timingpoint.domain.system;

import oss.relay.eventtiming.timingpoint.domain.node.TimingNode;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNodeList;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import oss.relay.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import oss.relay.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import oss.relay.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import oss.relay.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates one TimingSystem.
 *
 * <p>The conductor owns its TimingNodes, their lifecycle and the functional-state
 * subscriptions used for system coordination. These fixed relationships are established
 * during construction, before activation starts execution.</p>
 *
 * <p>Only a TimingNode property update containing the functional-state property wakes the
 * control task. The subsequent run
 * reads every TimingNode's current functional state and asks AntennaManager to adopt the
 * desired inventory state. It does not retain a second copy of node state or perform
 * device operations directly.</p>
 */
public final class TimingSystemConductor extends AbstractConductor {
    private static final Logger LOG = LoggerFactory.getLogger(TimingSystemConductor.class);

    private final TimingNodeList nodes;
    private final AntennaManager antennaManager;
    private final SerialTaskRunner taskLane;
    private final MainTask mainTask = new MainTask();

    public TimingSystemConductor(
            TimingNodeList timingNodes,
            AntennaManager antennaManager,
            SerialTaskRunner coordinationLane) {

        super("TimingSystemConductor", coordinationLane);

        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException("timingNodes must not be empty");
        }

        this.nodes = timingNodes;
        this.antennaManager = antennaManager;
        this.taskLane = coordinationLane;

        for (TimingNode node : timingNodes) {
            registerComponent(
                    "TimingNode " + node.nodeId().value(),
                    node);
            node.PROPERTIES.CHANGED.subscribe(
                    update -> update.contains(TimingNode.FUNCTIONAL_STATE),
                    ignored -> wakeMainTask());
        }
    }

    /** Wakes the main task after one TimingNode functional-state change. */
    private void wakeMainTask() {

        // onActivated() requests the initial run; later status changes wake reconciliation.
        if (coordinationLane().isRunning()) {
            mainTask.wake(taskLane);
        }
    }

    @Override
    protected void onActivated() {

        mainTask.wake(taskLane);
    }

    private final class MainTask extends AbstractTask {

        @Override
        protected void resetForRun() {
            // Main task state is owned by TimingSystemConductor.
        }

        @Override
        public TaskStep runStep() {
            return evaluateNodeStatus();
        }

        @Override
        protected void onRunCompleted(Throwable failure) {
            if (failure != null) {
                LOG.error("TimingSystem Conductor main task failed", failure);
            }
        }
    }

    private TaskStep evaluateNodeStatus() {

        boolean required = false;
        for (TimingNode node : nodes) {
            if (node.functionalState() == FunctionalState.OPEN) {
                required = true;
            }
        }
        if (antennaManager != null) {
            antennaManager.setInventoryRequired(required);
        }
        return TaskStep.done();
    }
}
