package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OperationException;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSettings;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SafeExecute;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.TaskSerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;

import java.util.Optional;
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
public final class TimingSystemConductor extends AbstractConductor {
    private static final Logger LOG = LoggerFactory.getLogger(TimingSystemConductor.class);

    private static final EventInput<TimingSystemConductor, Status> NODE_STATUS_CHANGED =
            EventInput.of(
                    (conductor, source) ->
                            source.subscribe(ignored -> conductor.wakeMainTask()));

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
                    node::activate,
                    node::deactivate);
            NODE_STATUS_CHANGED.bind(this, node.statusChangedEvent());
        }
    }

    /** Wakes the main task after one TimingNode status change. */
    private void wakeMainTask() {

        /*
         * A TimingNode may finish its first recovery while this Conductor is
         * still activating its children. onActivated() always requests the first
         * main-task run, so an earlier event does not need to be retained.
         */
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
            Optional<Status> status = SafeExecute.callOrEmpty(
                    node::status,
                    OperationException.class,
                    ex -> ex.reason() == OperationException.Reason.UNAVAILABLE);

            /*
             * Recovery is asynchronous. Until the node is available, publish no
             * partial system decision; its first Status event wakes MainTask again.
             */
            if (!status.isPresent()) {
                return TaskStep.done();
            }

            if (status.get().state() == State.OPEN) {
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
