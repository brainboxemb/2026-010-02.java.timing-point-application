package io.github.brainboxemb.eventtiming.timingpoint.application.logic;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes the mechanical lifecycle of application components registered by
 * {@link AbstractConductor}.
 *
 * <p>This helper owns no application startup policy. The concrete Conductor decides which
 * components participate and when lifecycle execution is requested. This class
 * only preserves activation order, rolls back components that already activated
 * when a later activation fails, and deactivates active components in reverse
 * order.</p>
 */
final class ComponentLifecycleManager {
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
     * Registers one component in activation order.
     *
     * <p>Registration is intentionally available only before activation. The
     * reverse of this order is used for rollback and normal deactivation.</p>
     */
    synchronized void register(
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
    synchronized void activateAll() {
        if (state == State.ACTIVE) {
            throw new IllegalStateException("Components are already active");
        }

        int activatedCount = 0;
        try {
            for (Component component : components) {
                LOG.info(
                        "Activating application component {}",
                        component.name);
                component.activate.run();
                activatedCount++;
                LOG.info(
                        "Activated application component {}",
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
    synchronized void deactivateAll() {
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
                        "Deactivating application component {}",
                        component.name);
                component.deactivate.run();
                LOG.info(
                        "Deactivated application component {}",
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
                "Application component activation failed after {} component(s); rolling back",
                activatedCount,
                originalFailure);

        for (int index = activatedCount - 1;
                index >= 0;
                index--) {
            Component component =
                    components.get(index);
            try {
                LOG.info(
                        "Rolling back application component {}",
                        component.name);
                component.deactivate.run();
            } catch (RuntimeException ex) {
                originalFailure.addSuppressed(ex);
            } catch (Error error) {
                originalFailure.addSuppressed(error);
            }
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
