package io.github.brainboxemb.eventtiming.timingpoint.application.logic;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reusable lifecycle base for an application Conductor.
 *
 * <p>The base owns only generic lifecycle mechanics:</p>
 *
 * <ul>
 *   <li>registered application-component activation order;</li>
 *   <li>rollback when activation fails;</li>
 *   <li>the supplied Application serial lane lifecycle;</li>
 *   <li>reverse-order component deactivation;</li>
 *   <li>preservation of cleanup failures as suppressed failures.</li>
 * </ul>
 *
 * <p>Concrete conductors keep application meaning. This class must not learn
 * about TimingNode, AntennaManager, providers, tracked properties or SI-01
 * rules.</p>
 */
public abstract class AbstractConductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(AbstractConductor.class);

    private final SerialExecutor applicationLane;
    private final ComponentLifecycleManager componentLifecycle =
            new ComponentLifecycleManager();

    protected AbstractConductor(
            SerialExecutor applicationLane) {
        if (applicationLane == null) {
            throw new IllegalArgumentException(
                    "applicationLane must not be null");
        }
        this.applicationLane = applicationLane;
    }

    /**
     * Registers one application component in activation order.
     *
     * <p>Concrete conductors use this only while they are being constructed.
     * Normal deactivation and activation rollback use the reverse order.</p>
     */
    protected final void registerComponent(
            String name,
            Runnable activate,
            Runnable deactivate) {
        componentLifecycle.register(
                name,
                activate,
                deactivate);
    }

    /**
     * Gives concrete application properties access to the one Application lane.
     *
     * <p>The returned lane is still owned by this base lifecycle. Concrete code
     * may schedule work on it but must not start or close it.</p>
     */
    protected final SerialExecutor applicationLane() {
        return applicationLane;
    }

    /**
     * Activates registered components, starts the Application lane and then
     * invokes the concrete startup hook.
     *
     * <p>If any phase fails, this method closes the lane and deactivates already
     * active components before rethrowing the original failure. Cleanup failures
     * are attached as suppressed failures.</p>
     */
    public final void activate() {
        LOG.info(
                "Starting application coordination");

        try {
            componentLifecycle.activateAll();
            applicationLane.start();
            onActivated();

            LOG.info(
                    "Application coordination started");
        } catch (RuntimeException ex) {
            cleanupAfterActivationFailure(ex);
            throw ex;
        } catch (Error error) {
            cleanupAfterActivationFailure(error);
            throw error;
        }
    }

    /**
     * Stops Application-lane work and then deactivates components in reverse
     * activation order.
     *
     * <p>Both cleanup phases are attempted. When more than one phase fails, the
     * first failure remains primary and later failures are suppressed.</p>
     */
    public final void deactivate() {
        LOG.info(
                "Stopping application coordination");

        Throwable firstFailure = null;

        try {
            applicationLane.close();
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
                "Application coordination stopped");
    }

    /**
     * Concrete startup hook called after all registered components are active
     * and the Application lane is running.
     *
     * <p>SI-01-specific startup actions such as initial tracked-property
     * reads belong here. Long-running device startup work belongs to the
     * component that owns that device.</p>
     */
    protected abstract void onActivated();

    private void cleanupAfterActivationFailure(
            Throwable originalFailure) {
        LOG.warn(
                "{} activation failed; cleaning up",
                getClass().getSimpleName(),
                originalFailure);

        try {
            applicationLane.close();
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
