package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.application.property.TimingNodeLifecycleProperty;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates application-wide behaviour between already constructed components.
 *
 * <p>Runtime composition owns object construction, event wiring and physical
 * execution resources. Conductor owns application-level lifecycle and the rules
 * that connect tracked application properties to component intent.</p>
 *
 * <p>{@link ComponentLifecycleManager} performs only ordered activation,
 * rollback and reverse deactivation. Concrete tracked values live under
 * {@code application.property}; generic tracking mechanics live under
 * {@code infra.property}.</p>
 */
public final class Conductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;
    private final SerialExecutor serialExecutor;
    private final ComponentLifecycleManager componentLifecycle =
            new ComponentLifecycleManager();
    private final TimingNodeLifecycleProperty timingNodeLifecycleProperty;

    /**
     * Creates one application coordinator.
     *
     * @param timingNode required TimingNode component
     * @param antennaManager optional antenna component; {@code null} when the
     *        composition contains no antennas
     * @param serialExecutor Conductor/Application logical serial lane on the
     *        Runtime-owned application worker
     */
    public Conductor(
            TimingNode timingNode,
            AntennaManager antennaManager,
            SerialExecutor serialExecutor) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        if (serialExecutor == null) {
            throw new IllegalArgumentException(
                    "serialExecutor must not be null");
        }

        this.timingNode = timingNode;
        this.antennaManager = antennaManager;
        this.serialExecutor = serialExecutor;

        timingNodeLifecycleProperty =
                new TimingNodeLifecycleProperty(
                        timingNode,
                        serialExecutor);
        timingNodeLifecycleProperty.onChange(
                this::applyTimingNodeLifecycle);

        componentLifecycle.register(
                "TimingNode " + timingNode.timingNodeId().value(),
                timingNode::activate,
                timingNode::deactivate);

        if (antennaManager != null) {
            componentLifecycle.register(
                    "AntennaManager",
                    antennaManager::activate,
                    antennaManager::deactivate);
        }
    }

    /**
     * Activates application components and initializes tracked application state.
     *
     * <p>Source events raised while components activate only mark their property
     * as changed. After component activation and required startup health work,
     * the Application lane starts and each property performs one authoritative
     * initial read before activation is reported complete.</p>
     */
    public void activate() {
        try {
            componentLifecycle.activateAll();

            if (antennaManager != null) {
                antennaManager.checkHealth();
                LOG.info(
                        "Antenna startup health check completed: health={}",
                        antennaManager.health());
            }

            serialExecutor.start();
            timingNodeLifecycleProperty.initialize();

            LOG.info(
                    "Conductor activated application components for TimingNode {}",
                    timingNode.timingNodeId().value());
        } catch (RuntimeException ex) {
            cleanupAfterActivationFailure(ex);
            throw ex;
        } catch (Error error) {
            cleanupAfterActivationFailure(error);
            throw error;
        }
    }

    /**
     * Stops Application-lane coordination, then deactivates application
     * components in reverse activation order.
     *
     * <p>The serial lane is drained before component shutdown so accepted
     * property changes cannot run against disappearing components.</p>
     */
    public void deactivate() {
        LOG.info(
                "Conductor deactivating application components for TimingNode {}",
                timingNode.timingNodeId().value());

        Throwable firstFailure = null;

        try {
            serialExecutor.close();
        } catch (RuntimeException ex) {
            firstFailure = ex;
        } catch (Error error) {
            firstFailure = error;
        }

        try {
            componentLifecycle.deactivateAll();
        } catch (RuntimeException ex) {
            firstFailure =
                    appendFailure(
                            firstFailure,
                            ex);
        } catch (Error error) {
            firstFailure =
                    appendFailure(
                            firstFailure,
                            error);
        }

        rethrow(firstFailure);
    }

    /**
     * Exposes the concrete application property for explicit Runtime event
     * wiring and current-state diagnostics.
     */
    public TimingNodeLifecycleProperty timingNodeLifecycleProperty() {
        return timingNodeLifecycleProperty;
    }

    /**
     * Application rule: TimingNode OPEN requires antenna inventory.
     *
     * <p>The property already performed authoritative read/change detection.
     * This method therefore contains only application meaning, not queue or
     * synchronization mechanics.</p>
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

    private void cleanupAfterActivationFailure(
            Throwable originalFailure) {
        try {
            serialExecutor.close();
        } catch (RuntimeException ex) {
            originalFailure.addSuppressed(ex);
        } catch (Error error) {
            originalFailure.addSuppressed(error);
        }

        try {
            componentLifecycle.deactivateAll();
        } catch (RuntimeException ex) {
            originalFailure.addSuppressed(ex);
        } catch (Error error) {
            originalFailure.addSuppressed(error);
        }
    }

    private static Throwable appendFailure(
            Throwable firstFailure,
            Throwable laterFailure) {
        if (firstFailure == null) {
            return laterFailure;
        }
        firstFailure.addSuppressed(laterFailure);
        return firstFailure;
    }

    private static void rethrow(
            Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw (Error) failure;
    }
}
