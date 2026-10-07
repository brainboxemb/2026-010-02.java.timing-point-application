package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.application.logic.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.property.TimingNodeStateProperty;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTaskController;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SI-01 application coordinator.
 *
 * <p>This class contains application meaning only. Generic activation,
 * rollback, Application-lane lifecycle and cleanup mechanics are inherited from
 * {@link AbstractConductor}.</p>
 *
 * <p>Cross-component events only wake this coordinator. Application decisions
 * are made from current authoritative state in {@link #runStep()}, so future
 * coordination rules remain in one readable control-task boundary rather than
 * being spread over event handlers.</p>
 *
 * <p>The current TimingNode-to-antenna rule is deliberately explicit:</p>
 *
 * <pre>
 * TimingNode OPEN          -> enable antenna inventory
 * TimingNode CLOSED/ERROR  -> disable antenna inventory
 * </pre>
 *
 * <p>Disabling inventory stops reading and removes external antenna power where
 * configured. It does not shut down the Antenna provider; application shutdown
 * is a separate component-lifecycle action.</p>
 */
public final class Conductor extends AbstractConductor
        implements CooperativeTask {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;
    private final TimingNodeStateProperty timingNodeStateProperty;
    private final CooperativeTaskController taskController;

    /**
     * TimingNode state for which the inventory rule was last accepted.
     *
     * <p>This is remembered input from TimingNode, not Conductor state. It only
     * prevents a coalesced control pass from repeating the same inventory
     * request.</p>
     */
    private volatile TimingNodeTypes.State lastHandledTimingNodeState;

    /**
     * Creates the SI-01 coordinator and declares the components/properties it
     * coordinates.
     *
     * @param timingNode required TimingNode component
     * @param antennaManager optional antenna component; {@code null} when the
     *        composition contains no antennas
     * @param applicationLane logical Application lane supplied by Runtime
     */
    public Conductor(
            TimingNode timingNode,
            AntennaManager antennaManager,
            SerialExecutor applicationLane) {
        super(applicationLane);

        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }

        this.timingNode = timingNode;
        this.antennaManager = antennaManager;

        timingNodeStateProperty =
                new TimingNodeStateProperty(
                        timingNode,
                        applicationLane());
        taskController =
                new CooperativeTaskController(
                        new SerialTaskRunner(
                                applicationLane()),
                        this,
                        this::onControlTaskFailure);

        timingNodeStateProperty.changedEvent()
                .subscribe(
                        ignored -> stateMachine.wake());

        registerComponent(
                "TimingNode " + timingNode.timingNodeId().value(),
                timingNode::activate,
                timingNode::deactivate);

        if (antennaManager != null) {
            registerComponent(
                    "AntennaManager",
                    antennaManager::activate,
                    antennaManager::deactivate);
        }
    }

    /**
     * Runs SI-01 startup work after registered components are active and the
     * Application lane is running.
     */
    @Override
    protected void onActivated() {
        TimingNodeTypes.State initialState =
                timingNodeStateProperty.initialize();

        /*
         * AntennaManager starts with inventory disabled. CLOSED/ERROR therefore
         * already match the physical requested state and need no control action.
         * Initial OPEN is different and wakes the same cooperative control
         * task used for later changes.
         */
        if (antennaManager == null) {
            lastHandledTimingNodeState = initialState;
            return;
        }

        if (initialState == TimingNodeTypes.State.OPEN) {
            lastHandledTimingNodeState = null;
            stateMachine.wake();
        } else {
            lastHandledTimingNodeState = initialState;
            LOG.info(
                    "TimingNode {} initial state {} -> inventory disabled",
                    timingNode.timingNodeId().value(),
                    initialState);
        }
    }

    /**
     * Exposes the concrete TimingNode state property for explicit Runtime event
     * wiring and current-state diagnostics.
     */
    public TimingNodeStateProperty timingNodeStateProperty() {
        return timingNodeStateProperty;
    }

    /**
     * Runs one application-coordination control pass.
     *
     * <p>The changed-event payload is deliberately not used here. This task
     * reads the latest authoritative TimingNode state, so several source events
     * may collapse into one control pass without replaying stale intermediate
     * callback decisions.</p>
     */
    @Override
    public TaskStep runStep() {
        if (antennaManager == null
                || !timingNodeStateProperty.initialized()) {
            return TaskStep.done();
        }

        TimingNodeTypes.State timingNodeState =
                timingNodeStateProperty.currentValue();

        if (timingNodeState == lastHandledTimingNodeState) {
            return TaskStep.done();
        }

        if (applyTimingNodeState(timingNodeState)) {
            lastHandledTimingNodeState = timingNodeState;
        }

        return TaskStep.done();
    }

    /**
     * Applies the current application rule for TimingNode state.
     *
     * @return true when the antenna manager accepted the requested state
     */
    private boolean applyTimingNodeState(
            TimingNodeTypes.State timingNodeState) {
        switch (timingNodeState) {
            case OPEN:
                LOG.info(
                        "TimingNode {} OPEN -> enable inventory",
                        timingNode.timingNodeId().value());

                if (antennaManager.requestEnableInventory()) {
                    return true;
                }

                LOG.warn(
                        "AntennaManager rejected enable-inventory request for TimingNode {} state OPEN managerState={}",
                        timingNode.timingNodeId().value(),
                        antennaManager.state());
                return false;

            case CLOSED:
            case ERROR:
                LOG.info(
                        "TimingNode {} {} -> disable inventory",
                        timingNode.timingNodeId().value(),
                        timingNodeState);

                if (antennaManager.requestDisableInventory()) {
                    return true;
                }

                LOG.warn(
                        "AntennaManager rejected disable-inventory request for TimingNode {} state {} managerState={}",
                        timingNode.timingNodeId().value(),
                        timingNodeState,
                        antennaManager.state());
                return false;

            default:
                throw new IllegalStateException(
                        "Unsupported TimingNode state "
                                + timingNodeState);
        }
    }

    /**
     * Cooperative-runner failures are execution failures of the coordinator,
     * not separate application transitions.
     */
    private void onControlTaskFailure(
            Throwable failure) {
        LOG.error(
                "Conductor control task failed",
                failure);
    }
}
