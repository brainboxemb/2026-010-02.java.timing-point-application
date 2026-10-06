package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.application.logic.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.property.TimingNodeLifecycleProperty;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
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
 * <p>The current application rule is:</p>
 *
 * <pre>
 * TimingNode lifecycle OPEN   -> antenna inventory required
 * TimingNode lifecycle other  -> antenna inventory not required
 * </pre>
 */
public final class Conductor extends AbstractConductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;
    private final TimingNodeLifecycleProperty timingNodeLifecycleProperty;

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

        timingNodeLifecycleProperty =
                new TimingNodeLifecycleProperty(
                        timingNode,
                        applicationLane());
        timingNodeLifecycleProperty.onChange(
                this::applyTimingNodeLifecycle);

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
        if (antennaManager != null) {
            antennaManager.checkHealth();
            LOG.info(
                    "Antenna startup health check completed: health={}",
                    antennaManager.health());
        }

        timingNodeLifecycleProperty.initialize();

        LOG.info(
                "SI-01 application coordination initialized for TimingNode {}",
                timingNode.timingNodeId().value());
    }

    /**
     * Exposes the concrete lifecycle property for explicit Runtime event
     * wiring and current-state diagnostics.
     */
    public TimingNodeLifecycleProperty timingNodeLifecycleProperty() {
        return timingNodeLifecycleProperty;
    }

    /**
     * Application rule: TimingNode OPEN requires antenna inventory.
     *
     * <p>The property already performed authoritative read/change detection.
     * This method therefore contains only the application decision.</p>
     */
    private void applyTimingNodeLifecycle(
            Lifecycle lifecycle) {
        if (antennaManager == null) {
            return;
        }

        boolean inventoryRequired =
                lifecycle == Lifecycle.OPEN;

        LOG.info(
                "TimingNode {} lifecycle {} sets antenna inventory required={}",
                timingNode.timingNodeId().value(),
                lifecycle,
                inventoryRequired);

        boolean accepted =
                antennaManager.requestInventoryEnabled(
                        inventoryRequired);

        if (!accepted) {
            LOG.warn(
                    "AntennaManager rejected inventory required={} for TimingNode {} lifecycle {} managerState={}",
                    inventoryRequired,
                    timingNode.timingNodeId().value(),
                    lifecycle,
                    antennaManager.state());
        }
    }
}
