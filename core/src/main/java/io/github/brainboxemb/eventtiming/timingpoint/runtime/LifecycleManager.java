package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Starts and stops an explicitly registered set of application lifecycle steps.
 *
 * <p>Registration order is startup order. Shutdown and startup rollback use the
 * reverse order. This class owns ordering and failure handling only; it does not
 * discover components or decide what belongs to the application.</p>
 */
final class LifecycleManager {

    private enum State {
        NEW,
        RUNNING,
        STOPPED
    }

    private static final class Step {
        private final String name;
        private final Runnable startAction;
        private final Runnable stopAction;

        private Step(
                String name,
                Runnable startAction,
                Runnable stopAction) {
            this.name = name;
            this.startAction = startAction;
            this.stopAction = stopAction;
        }
    }

    private final List<Step> steps =
            new ArrayList<Step>();

    private State state = State.NEW;

    /**
     * Registers one explicit lifecycle step.
     *
     * <p>Optional components are handled at the composition point. Null actions
     * are rejected rather than silently hiding incomplete wiring.</p>
     */
    synchronized void register(
            String name,
            Runnable startAction,
            Runnable stopAction) {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Lifecycle steps can only be registered before startup");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "name must not be blank");
        }
        if (startAction == null) {
            throw new IllegalArgumentException(
                    "startAction must not be null");
        }
        if (stopAction == null) {
            throw new IllegalArgumentException(
                    "stopAction must not be null");
        }

        steps.add(
                new Step(
                        name.trim(),
                        startAction,
                        stopAction));
    }

    /**
     * Starts every registered step in registration order.
     *
     * <p>If startup fails, already-started earlier steps are stopped in reverse
     * order. Each component remains responsible for cleaning up any partial work
     * performed by its own failing start method.</p>
     */
    synchronized void startAll() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "Lifecycle can only start from NEW; current state="
                            + state);
        }

        int startedCount = 0;
        try {
            for (Step step : steps) {
                step.startAction.run();
                startedCount++;
            }
            state = State.RUNNING;
        } catch (RuntimeException ex) {
            rollbackStarted(
                    startedCount,
                    ex);
            state = State.STOPPED;
            throw ex;
        } catch (Error error) {
            rollbackStarted(
                    startedCount,
                    error);
            state = State.STOPPED;
            throw error;
        }
    }

    /**
     * Stops every registered step in reverse registration order.
     *
     * <p>When the application was never started, no component stop action is
     * invoked; constructed resources that need pre-start cleanup should be
     * closed separately by their owner.</p>
     */
    synchronized void stopAll() {
        if (state == State.STOPPED) {
            return;
        }
        if (state == State.NEW) {
            state = State.STOPPED;
            return;
        }

        Throwable firstFailure = null;
        for (int index = steps.size() - 1;
                index >= 0;
                index--) {
            Step step = steps.get(index);
            try {
                step.stopAction.run();
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

        state = State.STOPPED;
        rethrow(firstFailure);
    }

    private void rollbackStarted(
            int startedCount,
            Throwable originalFailure) {
        for (int index = startedCount - 1;
                index >= 0;
                index--) {
            Step step = steps.get(index);
            try {
                step.stopAction.run();
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
