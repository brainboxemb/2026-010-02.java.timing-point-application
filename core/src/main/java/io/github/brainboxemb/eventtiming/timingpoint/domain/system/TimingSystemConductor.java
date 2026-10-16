package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.FunctionalState;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSettings;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.TaskSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates one TimingSystem.
 *
 * <p>The conductor owns its TimingNodes, their lifecycle and the status subscriptions used for system coordination. These fixed relationships are
 * established during construction, before activation starts execution.</p>
 *
 * <p>A TimingNode property-change event only wakes the control task. The subsequent run
 * reads every TimingNode's current functional state and publishes the desired inventory state
 * through the shared AntennaSettings. It does not retain a second copy of
 * node state or invoke AntennaManager directly.</p>
 */
public final class TimingSystemConductor extends AbstractConductor {
    private static final Logger LOG = LoggerFactory.getLogger(TimingSystemConductor.class);

    private final TimingNodeList nodes;
    private final AntennaSettings antennaSettings;
    private final TaskSerialLane taskLane;
    private final MainTask mainTask = new MainTask();

    public TimingSystemConductor(
            TimingNodeList timingNodes,
            AntennaSettings antennaSettings,
            TaskSerialLane coordinationLane) {

        super(coordinationLane);

        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException("timingNodes must not be empty");
        }

        this.nodes = timingNodes;
        this.antennaSettings = antennaSettings;
        this.taskLane = coordinationLane;

        for (TimingNode node : timingNodes) {
            registerComponent(
                    "TimingNode " + node.timingNodeId().value(),
                    node);
            node.PROPERTIES.CHANGED.subscribe(ignored -> wakeMainTask());
        }
    }

    /** Wakes the main task after one TimingNode status change. */
    private void wakeMainTask() {

        // onActivated() requests the initial run; later status changes wake reconciliation.
        if (coordinationLane().state() == SerialLane.State.RUNNING) {
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
        if (antennaSettings != null) {
            final boolean inventoryRequired = required;
            Consumer<AntennaSettings.Edit> settingsUpdate =
                    edit -> edit.inventoryEnabled(inventoryRequired);

            antennaSettings.update(settingsUpdate);
        }
        return TaskStep.done();
    }
}
