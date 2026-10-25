package oss.relay.eventtiming.timingpoint.infra.lifecycle;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes ordered activation and reverse deactivation for components
 * registered by an owning coordinator.
 *
 * <p>This helper owns no startup policy. The owning Application or TimingSystem
 * coordinator decides which components participate and when lifecycle execution
 * is requested. Internal lifecycle is driven through {@link LifecycleComponent}
 * without exposing activate/deactivate methods in each component's public API.
 * This class preserves activation order and deactivates active components in
 * reverse order. Each component owns its own operational state; failure to
 * activate one component does not prevent independent components from receiving
 * their activation.</p>
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
     * Activates every registered component in registration order.
     *
     * <p>A component whose activation throws remains inactive. The failure is
     * logged and activation continues with the next independent component.
     * Operational startup problems should normally be represented by the
     * component's own state rather than by an escaped activation exception.</p>
     */
    public synchronized void activateAll() {
        if (state == State.ACTIVE) {
            throw new IllegalStateException("Components are already active");
        }

        for (Component component : components) {
            LOG.info(
                    "Activating {}",
                    component.name);
            try {
                component.lifecycleComponent.activateLifecycle();
                LOG.info(
                        "{} activated",
                        component.name);
            } catch (RuntimeException ex) {
                LOG.error(
                        "{} activation failed; continuing with remaining components",
                        component.name,
                        ex);
            }
        }
        state = State.ACTIVE;
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
