package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.application.logic.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.property.TimingNodeStateProperty;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.State;
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
 * coordination rules remain in one readable state-machine boundary rather than
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
    private final CooperativeTaskController stateMachine;

    /**
     * Last TimingNode state reconciled by this Conductor.
     *
     * <p>This is application state, not scheduling state. Repeated wake-ups may
     * therefore safely coalesce without repeating an already applied rule.</p>
     */
    private volatile State reconciledTimingNodeState;

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
        stateMachine =
                new CooperativeTaskController(
                        new SerialTaskRunner(
                                applicationLane()),
                        this,
                        this::onStateMachineFailure);

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
        State initialState =
                timingNodeStateProperty.initialize();

        /*
         * AntennaManager starts with inventory disabled. CLOSED/ERROR therefore
         * already match the physical requested state and need no control action.
         * Initial OPEN is different and wakes the same state machine used for
         * later changes.
         */
        if (antennaManager == null) {
            reconciledTimingNodeState = initialState;
            return;
        }

        if (initialState == State.OPEN) {
            reconciledTimingNodeState = null;
            stateMachine.wake();
        } else {
            reconciledTimingNodeState = initialState;
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
     * Runs one application-coordination transition.
     *
     * <p>The changed-event payload is deliberately not used here. The state
     * machine reads the latest authoritative tracked value, so several source
     * events may collapse into one reconciliation without replaying stale
     * intermediate callback decisions.</p>
     */
    @Override
    public TaskStep runStep() {
        if (antennaManager == null
                || !timingNodeStateProperty.initialized()) {
            return TaskStep.done();
        }

        State state =
                timingNodeStateProperty.currentValue();

        if (state == reconciledTimingNodeState) {
            return TaskStep.done();
        }

        if (applyTimingNodeState(state)) {
            reconciledTimingNodeState = state;
        }

        return TaskStep.done();
    }

    /**
     * Applies the current application rule for TimingNode state.
     *
     * @return true when the antenna manager accepted the requested state
     */
    private boolean applyTimingNodeState(
            State state) {
        switch (state) {
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
                        state);

                if (antennaManager.requestDisableInventory()) {
                    return true;
                }

                LOG.warn(
                        "AntennaManager rejected disable-inventory request for TimingNode {} state {} managerState={}",
                        timingNode.timingNodeId().value(),
                        state,
                        antennaManager.state());
                return false;

            default:
                throw new IllegalStateException(
                        "Unsupported TimingNode state "
                                + state);
        }
    }

    /**
     * Cooperative-runner failures are execution failures of the coordinator,
     * not separate application transitions.
     */
    private void onStateMachineFailure(
            Throwable failure) {
        LOG.error(
                "Conductor state machine failed",
                failure);
    }
}
