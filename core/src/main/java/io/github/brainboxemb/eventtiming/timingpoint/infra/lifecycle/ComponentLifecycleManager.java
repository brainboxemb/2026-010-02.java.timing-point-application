package io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes ordered activation, rollback and reverse deactivation for components
 * registered by an owning coordinator.
 *
 * <p>This helper owns no startup policy. The owning Application or TimingSystem
 * coordinator decides which components participate and when lifecycle execution
 * is requested. Internal lifecycle is driven through {@link LifecycleComponent}
 * without exposing activate/deactivate methods in each component's public API.
 * This class preserves activation order, rolls back components that already
 * activated when a later activation fails, and deactivates active components in
 * reverse order.</p>
 */
public final class ComponentLifecycleManager {
    private static final Logger LOG =
            LoggerFactory.getLogger(ComponentLifecycleManager.class);

    private enum State {
        NEW,
        ACTIVE,
        INACTIVE
    }

    private static final class Component {
        private final String name;
        private final LifecycleComponent lifecycleComponent;

        private Component(
                String name,
                LifecycleComponent lifecycleComponent) {
            this.name = name;
            this.lifecycleComponent = lifecycleComponent;
        }
    }

    private final List<Component> components =
            new ArrayList<Component>();

    private State state = State.NEW;

    /** Registers one lifecycle component using its concrete class name for diagnostics. */
    public synchronized void register(
            LifecycleComponent component) {

        if (component == null) {
            throw new IllegalArgumentException(
                    "component must not be null");
        }
        register(
                componentName(component),
                component);
    }

    /**
     * Registers one lifecycle component with an explicit diagnostic name.
     *
     * <p>Use this overload when several components of the same class participate,
     * for example multiple TimingNodes.</p>
     */
    public synchronized void register(
            String name,
            LifecycleComponent component) {

        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Components can only be registered before activation");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "name must not be blank");
        }
        if (component == null) {
            throw new IllegalArgumentException(
                    "component must not be null");
        }

        components.add(
                new Component(
                        name.trim(),
                        component));
    }

    /**
     * Activates registered components in order.
     *
     * <p>If activation fails, only components that completed activation are
     * deactivated, in reverse order. The original failure remains primary and
     * rollback failures are attached as suppressed failures.</p>
     */
    public synchronized void activateAll() {
        if (state == State.ACTIVE) {
            throw new IllegalStateException("Components are already active");
        }

        int activatedCount = 0;
        try {
            for (Component component : components) {
                LOG.info(
                        "Activating {}",
                        component.name);
                component.lifecycleComponent.activateLifecycle();
                activatedCount++;
                LOG.info(
                        "{} activated",
                        component.name);
            }
            state = State.ACTIVE;
        } catch (RuntimeException ex) {
            rollback(
                    activatedCount,
                    ex);
            state = State.INACTIVE;
            throw ex;
        } catch (Error error) {
            rollback(
                    activatedCount,
                    error);
            state = State.INACTIVE;
            throw error;
        }
    }

    /**
     * Deactivates active components in reverse activation order.
     */
    public synchronized void deactivateAll() {
        if (state == State.INACTIVE) {
            return;
        }

        if (state == State.NEW) {
            state = State.INACTIVE;
        } else {
            Throwable firstFailure = null;
            for (int index = components.size() - 1;
                    index >= 0;
                    index--) {
                Component component =
                        components.get(index);
                try {
                    LOG.info(
                            "Deactivating {}",
                            component.name);
                    component.lifecycleComponent.deactivateLifecycle();
                    LOG.info(
                            "{} deactivated",
                            component.name);
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
            }

            state = State.INACTIVE;
            rethrow(firstFailure);
        }
    }

    private void rollback(
            int activatedCount,
            Throwable originalFailure) {
        LOG.warn(
                "Component activation failed after {} component(s); rolling back",
                activatedCount,
                originalFailure);

        for (int index = activatedCount - 1;
                index >= 0;
                index--) {
            Component component =
                    components.get(index);
            try {
                LOG.info(
                        "Rolling back {}",
                        component.name);
                component.lifecycleComponent.deactivateLifecycle();
            } catch (RuntimeException ex) {
                originalFailure.addSuppressed(ex);
            } catch (Error error) {
                originalFailure.addSuppressed(error);
            }
        }
    }

    private static String componentName(Object component) {
        String simpleName =
                component.getClass().getSimpleName();
        if (simpleName == null || simpleName.trim().isEmpty()) {
            return component.getClass().getName();
        }
        return simpleName;
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
