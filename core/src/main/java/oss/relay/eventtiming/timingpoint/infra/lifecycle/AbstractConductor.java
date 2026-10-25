package oss.relay.eventtiming.timingpoint.infra.lifecycle;

import oss.relay.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable lifecycle base for a system Conductor.
 *
 * <p>The base owns only generic lifecycle mechanics:</p>
 *
 * <ul>
 *   <li>registered application-component activation order;</li>
 *   <li>the supplied Application serial lane lifecycle;</li>
 *   <li>reverse-order component deactivation;</li>
 *   <li>preservation of cleanup failures as suppressed failures.</li>
 * </ul>
 *
 * <p>Concrete conductors keep application meaning. This class must not learn
 * about TimingNode, AntennaManager, providers, tracked properties or SI-01
 * rules.</p>
 */
public abstract class AbstractConductor extends LifecycleComponent {
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractConductor.class);

    private final String diagnosticName;
    private final SerialTaskRunner coordinationLane;
    private final ComponentLifecycleManager componentLifecycle =
            new ComponentLifecycleManager();

    protected AbstractConductor(
            String diagnosticName,
            SerialTaskRunner coordinationLane) {
        if (diagnosticName == null || diagnosticName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "diagnosticName must not be blank");
        }
        if (coordinationLane == null) {
            throw new IllegalArgumentException(
                    "coordinationLane must not be null");
        }
        this.diagnosticName = diagnosticName.trim();
        this.coordinationLane = coordinationLane;
    }

    /**
     * Registers one component in activation order.
     *
     * <p>Concrete conductors use this only while they are being constructed.
     * Normal deactivation uses the reverse order.</p>
     */
    protected final void registerComponent(
            String name,
            LifecycleComponent component) {
        componentLifecycle.register(
                name,
                component);
    }

    /**
     * Gives the concrete Conductor access to its one coordination lane.
     *
     * <p>The returned lane is still owned by this base lifecycle. Concrete code
     * may schedule work on it but must not start or close it.</p>
     */
    protected final SerialTaskRunner coordinationLane() {
        return coordinationLane;
    }

    /**
     * Activates registered components, starts the coordination lane and then
     * invokes the concrete startup hook.
     *
     * <p>There is deliberately no generic activation rollback here. Registered
     * components own their own operational state and activation behaviour.</p>
     */
    @Override
    protected final void onActivate() {
        LOG.info(
                "Starting {}",
                diagnosticName);

        componentLifecycle.activateAll();
        coordinationLane.start();
        onActivated();

        LOG.info(
                "{} started",
                diagnosticName);
    }
    /**
     * Stops coordination-lane work and then deactivates components in reverse
     * activation order.
     *
     * <p>Both cleanup phases are attempted. When more than one phase fails, the
     * first failure remains primary and later failures are suppressed.</p>
     */
    @Override
    protected final void onDeactivate() {
        LOG.info(
                "Stopping {}",
                diagnosticName);

        Throwable firstFailure = null;

        try {
            coordinationLane.close();
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

        LOG.info(
                "{} stopped",
                diagnosticName);
    }

    /**
     * Concrete startup hook called after all registered components are active
     * and the coordination lane is running.
     *
     * <p>SI-01-specific startup actions such as waking the first control run
     * belong here. Potentially blocking current-state acquisition belongs in
     * that explicit control behaviour, not hidden inside this lifecycle hook.
     * Long-running device startup work belongs to the component that owns that
     * device.</p>
     */
    protected abstract void onActivated();

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
