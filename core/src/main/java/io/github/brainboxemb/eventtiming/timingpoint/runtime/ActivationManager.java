package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Activates registered application components in order and deactivates them in
 * reverse order.
 *
 * <p>This class coordinates component activation only. It does not start or stop
 * Runtime execution infrastructure and it does not discover components.</p>
 */
final class ActivationManager {

    private enum State {
        NEW,
        ACTIVE,
        INACTIVE
    }

    private static final class Component {
        private final Runnable activate;
        private final Runnable deactivate;

        private Component(
                Runnable activate,
                Runnable deactivate) {
            this.activate = activate;
            this.deactivate = deactivate;
        }
    }

    private final List<Component> components =
            new ArrayList<Component>();

    private State state = State.NEW;

    synchronized void register(
            Runnable activate,
            Runnable deactivate) {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Components can only be registered before activation");
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
                        activate,
                        deactivate));
    }

    synchronized void activateAll() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Components can only activate from NEW; current state="
                            + state);
        }

        int activatedCount = 0;
        try {
            for (Component component : components) {
                component.activate.run();
                activatedCount++;
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
            try {
                components.get(index)
                        .deactivate
                        .run();
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
        for (int index = activatedCount - 1;
                index >= 0;
                index--) {
            try {
                components.get(index)
                        .deactivate
                        .run();
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
