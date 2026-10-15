package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.taskrunners.YieldingTaskRunner;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Coalescing wake-up controller for one long-lived cooperative control task.
 *
 * <p>External events call {@link #wake()}; they do not execute transition
 * logic. At most one run is active at a time. Any number of wake-ups arriving
 * during that run collapse into one later run, which reads the owner's current
 * authoritative state again.</p>
 *
 * <p>This class owns only scheduling state ({@code running}/{@code wakePending}).
 * The supplied {@link YieldingTask} remains the sole owner of application,
 * domain or device state.</p>
 */
public final class YieldingTaskController {
    private final YieldingTaskRunner runner;
    private final YieldingTask task;
    private final Consumer<Throwable> failureHandler;

    private boolean running;
    private boolean wakePending;

    /** Creates coalescing wake-up control around one reusable yielding task. */
    public YieldingTaskController(
            YieldingTaskRunner runner, YieldingTask task, Consumer<Throwable> failureHandler) {
        checkArgument(runner != null, "runner must not be null");
        checkArgument(task != null, "task must not be null");
        checkArgument(failureHandler != null, "failureHandler must not be null");

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

    /** Reports whether one controlled task run is currently active. */
    public synchronized boolean isRunning() {
        return running;
    }

    /** Reports whether one coalesced follow-up wake is remembered. */
    public synchronized boolean wakePending() {
        return wakePending;
    }

    /** Starts one runner-owned execution and attaches completion handling. */
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

    /** Completes one run and starts the coalesced follow-up only after successful completion. */
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
        } else if (restart) {
            startRun();
        }
    }
}
