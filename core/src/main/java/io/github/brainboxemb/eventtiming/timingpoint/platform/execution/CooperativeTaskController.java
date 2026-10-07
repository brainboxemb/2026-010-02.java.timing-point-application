package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Coalescing wake-up controller for one long-lived cooperative control task.
 *
 * <p>External events call {@link #wake()}; they do not execute transition
 * logic. At most one run is active at a time. Any number of wake-ups arriving
 * during that run collapse into one later run, which reads the owner's current
 * authoritative state again.</p>
 *
 * <p>This class owns only scheduling state ({@code running}/{@code wakePending}).
 * The supplied {@link CooperativeTask} remains the sole owner of application,
 * domain or device state.</p>
 */
public final class CooperativeTaskController {
    private final CooperativeTaskRunner runner;
    private final CooperativeTask task;
    private final Consumer<Throwable> failureHandler;

    private boolean running;
    private boolean wakePending;

    public CooperativeTaskController(
            CooperativeTaskRunner runner,
            CooperativeTask task,
            Consumer<Throwable> failureHandler) {
        if (runner == null) {
            throw new IllegalArgumentException(
                    "runner must not be null");
        }
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }
        if (failureHandler == null) {
            throw new IllegalArgumentException(
                    "failureHandler must not be null");
        }

        this.runner = runner;
        this.task = task;
        this.failureHandler = failureHandler;
    }

    /**
     * Requests one current-state pass of the controlled task.
     *
     * <p>Repeated calls while a run is active are coalesced into one follow-up
     * run rather than one queued run per event.</p>
     */
    public void wake() {
        synchronized (this) {
            wakePending = true;
            if (running) {
                return;
            }

            wakePending = false;
            running = true;
        }

        startRun();
    }

    /**
     * Drops a remembered follow-up wake without cancelling a step that is
     * already admitted or running.
     */
    public synchronized void clearPendingWake() {
        wakePending = false;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized boolean wakePending() {
        return wakePending;
    }

    private void startRun() {
        final CompletableFuture<Void> run;
        try {
            run = runner.runTask(
                    task);
        } catch (RuntimeException ex) {
            runCompleted(
                    null,
                    ex);
            return;
        } catch (Error error) {
            runCompleted(
                    null,
                    error);
            throw error;
        }

        run.whenComplete(
                this::runCompleted);
    }

    private void runCompleted(
            Void ignored,
            Throwable failure) {
        final boolean restart;

        synchronized (this) {
            running = false;

            restart = failure == null && wakePending;
            if (restart) {
                wakePending = false;
                running = true;
            }
        }

        if (failure != null) {
            if (!(failure instanceof CancellationException)) {
                failureHandler.accept(
                        failure);
            }
            return;
        }

        if (restart) {
            startRun();
        }
    }
}
