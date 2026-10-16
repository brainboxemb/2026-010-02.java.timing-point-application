package io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle;

/**
 * Base type for internal software components with infrastructure lifecycle.
 *
 * <p>The lifecycle driver methods are intentionally package-private so component
 * lifecycle does not become part of each component's public functional API.
 * {@link ComponentLifecycleManager} is the normal owner that drives these
 * methods. Concrete components implement only the protected hooks.</p>
 */
public abstract class LifecycleComponent {
    private volatile boolean active;

    /** Drives component activation from lifecycle infrastructure. */
    final void activateLifecycle() {

        onActivate();
        active = true;
    }

    /** Drives component deactivation from lifecycle infrastructure. */
    final void deactivateLifecycle() {

        active = false;
        onDeactivate();
    }

    /** Returns whether activation completed and deactivation has not started. */
    protected final boolean isActive() {
        return active;
    }

    /** Component-specific activation hook. */
    protected abstract void onActivate();

    /** Component-specific deactivation hook. */
    protected abstract void onDeactivate();
}
