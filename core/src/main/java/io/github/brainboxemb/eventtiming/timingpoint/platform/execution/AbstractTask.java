package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for reusable cooperative tasks.
 *
 * <p>This class owns the execution lifecycle that is identical for every
 * reusable task: one current run, start, cancellation and completion. Concrete
 * tasks own only their state-machine data and implement {@link #runStep()}.</p>
 *
 * <p>The current {@link CompletableFuture} is deliberately private. It is an
 * execution handle returned by {@link ScheduledTaskRunner}; it is not domain
 * state and should not leak into concrete task implementations.</p>
 */
public abstract class AbstractTask implements CooperativeTask {

    private CompletableFuture<Void> currentRun;

    /**
     * Starts a new run.
     *
     * @return false when this task already has a run in progress
     */
    public final synchronized boolean start(ScheduledTaskRunner taskRunner) {
        if (taskRunner == null) {
            throw new IllegalArgumentException("taskRunner must not be null");
        }
        if (isRunning()) {
            return false;
        }

        resetForRun();

        CompletableFuture<Void> run = taskRunner.runTask(this);
        currentRun = run;
        run.whenComplete((ignored, failure) -> runCompleted(run, failure));
        return true;
    }

    /** Cancels the current run when one is active. */
    public final synchronized void cancel() {
        if (currentRun != null && !currentRun.isDone()) {
            currentRun.cancel(true);
        }
    }

    /** Returns whether this task currently has an unfinished run. */
    public final synchronized boolean isRunning() {
        return currentRun != null && !currentRun.isDone();
    }

    /**
     * Resets concrete state immediately before a new run is admitted.
     */
    protected abstract void resetForRun();

    /**
     * Called after one run has completed normally or exceptionally.
     *
     * <p>Cancellation is filtered by this base class and is not reported here.</p>
     */
    protected void onRunCompleted(Throwable failure) {
        // Most tasks need no completion action.
    }

    private void runCompleted(CompletableFuture<Void> run, Throwable failure) {
        synchronized (this) {
            /*
             * A stale completion must never clear a newer run. In normal use a
             * reusable task cannot restart until the previous Future is done,
             * but keeping the identity check here makes that ownership explicit.
             */
            if (currentRun == run) {
                currentRun = null;
            }
        }

        if (failure instanceof CancellationException) {
            return;
        }

        onRunCompleted(failure);
    }
}
