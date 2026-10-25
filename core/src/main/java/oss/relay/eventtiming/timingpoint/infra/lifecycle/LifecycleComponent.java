package oss.relay.eventtiming.timingpoint.infra.lifecycle;

import java.util.function.Supplier;

/**
 * Base type for internal software components with infrastructure lifecycle.
 *
 * <p>The lifecycle driver methods are intentionally package-private so component
 * lifecycle does not become part of each component's public functional API.
 * {@link ComponentLifecycleManager} is the normal owner that drives these
 * methods. Concrete components implement only the protected hooks.</p>
 */
public abstract class LifecycleComponent {
    private final Supplier<? extends RuntimeException> inactiveFailure;
    private volatile boolean active;

    /** Creates a component with the default inactive-state failure. */
    protected LifecycleComponent() {
        this(() -> new IllegalStateException("Component is not active"));
    }

    /** Creates a component with its caller-visible inactive-state failure. */
    protected LifecycleComponent(Supplier<? extends RuntimeException> inactiveFailure) {
        if (inactiveFailure == null) {
            throw new IllegalArgumentException("inactiveFailure must not be null");
        }
        this.inactiveFailure = inactiveFailure;
    }

    /** Drives component activation from lifecycle infrastructure. */
    final void activateLifecycle() {

        onActivate();
        active = true;
    }

    /** Drives deactivation only for a component whose activation completed. */
    final void deactivateLifecycle() {

        if (!active) {
            return;
        }
        active = false;
        onDeactivate();
    }

    /** Returns whether activation completed and deactivation has not started. */
    protected final boolean isActive() {
        return active;
    }

    /** Fails with the configured component contract while this component is inactive. */
    protected final void failIfInactive() {
        if (!active) {
            throw inactiveFailure.get();
        }
    }

    /** Component-specific activation hook. */
    protected abstract void onActivate();

    /** Component-specific deactivation hook. */
    protected abstract void onDeactivate();
}
