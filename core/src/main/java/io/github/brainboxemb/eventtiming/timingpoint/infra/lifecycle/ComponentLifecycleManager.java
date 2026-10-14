package io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes ordered activation, rollback and reverse deactivation for components
 * registered by an owning coordinator.
 *
 * <p>This helper owns no startup policy. The owning Application or TimingSystem
 * coordinator decides which components participate and when lifecycle execution is requested. This class
 * only preserves activation order, rolls back components that already activated
 * when a later activation fails, and deactivates active components in reverse
 * order.</p>
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
        private final Runnable activate;
        private final Runnable deactivate;

        private Component(
                String name,
                Runnable activate,
                Runnable deactivate) {
            this.name = name;
            this.activate = activate;
            this.deactivate = deactivate;
        }
    }

    private final List<Component> components =
            new ArrayList<Component>();

    private State state = State.NEW;

    /**
     * Registers one component using its concrete class name for diagnostics.
     *
     * <p>The lifecycle methods remain explicit compile-time method references.
     * This avoids requiring a common lifecycle interface while also avoiding
     * reflection-based discovery of activate/deactivate methods.</p>
     */
    public synchronized <T> void register(
            T component,
            Consumer<T> activate,
            Consumer<T> deactivate) {
        if (component == null) {
            throw new IllegalArgumentException(
                    "component must not be null");
        }
        if (activate == null) {
            throw new IllegalArgumentException(
                    "activate must not be null");
        }
        if (deactivate == null) {
            throw new IllegalArgumentException(
                    "deactivate must not be null");
        }

        register(
                componentName(component),
                () -> activate.accept(component),
                () -> deactivate.accept(component));
    }

    /**
     * Registers one component in activation order with an explicit diagnostic
     * name.
     *
     * <p>Use this overload when the class name alone is not sufficiently
     * descriptive, for example when several TimingNodes participate.</p>
     */
    public synchronized void register(
            String name,
            Runnable activate,
            Runnable deactivate) {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Components can only be registered before activation");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "name must not be blank");
        }
        if (activate == null) {
            throw new IllegalArgumentException(
                    "activate must not be null");
        }
        if (deactivate == null) {
            throw new IllegalArgumentException(
                    "deactivate must not be null");
        }

        components.add(
                new Component(
                        name.trim(),
                        activate,
                        deactivate));
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
                component.activate.run();
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
            return;
        }

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
                component.deactivate.run();
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
                component.deactivate.run();
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
