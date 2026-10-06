package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.application.logic.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.property.TimingNodeStateProperty;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SI-01 application coordinator.
 *
 * <p>This class contains application meaning only. Generic activation,
 * rollback, Application-lane lifecycle and cleanup mechanics are inherited from
 * {@link AbstractConductor}.</p>
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
public final class Conductor extends AbstractConductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;
    private final TimingNodeStateProperty timingNodeStateProperty;

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
        timingNodeStateProperty.changedEvent()
                .subscribe(
                        this::onTimingNodeStateChanged);

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
         * AntennaManager starts with inventory disabled. Do not turn an initial
         * CLOSED/ERROR state into a redundant disable request. Initial OPEN is
         * different: it is a real requested setting and may remain pending
         * while the asynchronous antenna self-test is still running.
         */
        if (initialState == State.OPEN) {
            onTimingNodeStateChanged(
                    initialState);
        } else if (antennaManager != null) {
            LOG.info(
                    "TimingNode {} initial state {} -> antenna inventory remains disabled",
                    timingNode.timingNodeId().value(),
                    initialState);
        }

        LOG.info(
                "SI-01 application coordination initialized for TimingNode {}",
                timingNode.timingNodeId().value());
    }

    /**
     * Exposes the concrete TimingNode state property for explicit Runtime event
     * wiring and current-state diagnostics.
     */
    public TimingNodeStateProperty timingNodeStateProperty() {
        return timingNodeStateProperty;
    }

    /**
     * Applies the application rule for one authoritative TimingNode state.
     *
     * <p>OPEN enables antenna inventory. CLOSED and ERROR explicitly disable
     * inventory, which stops reading and powers down antennas where external
     * power control is configured.</p>
     */
    private void onTimingNodeStateChanged(
            State state) {
        if (antennaManager == null) {
            return;
        }

        switch (state) {
            case OPEN:
                LOG.info(
                        "TimingNode {} state OPEN -> enable antenna inventory",
                        timingNode.timingNodeId().value());

                if (!antennaManager.requestEnableInventory()) {
                    LOG.warn(
                            "AntennaManager rejected enable-inventory request for TimingNode {} state OPEN managerState={}",
                            timingNode.timingNodeId().value(),
                            antennaManager.state());
                }
                return;

            case CLOSED:
            case ERROR:
                LOG.info(
                        "TimingNode {} state {} -> disable antenna inventory",
                        timingNode.timingNodeId().value(),
                        state);

                if (!antennaManager.requestDisableInventory()) {
                    LOG.warn(
                            "AntennaManager rejected disable-inventory request for TimingNode {} state {} managerState={}",
                            timingNode.timingNodeId().value(),
                            state,
                            antennaManager.state());
                }
                return;

            default:
                throw new IllegalStateException(
                        "Unsupported TimingNode state "
                                + state);
        }
    }
}
