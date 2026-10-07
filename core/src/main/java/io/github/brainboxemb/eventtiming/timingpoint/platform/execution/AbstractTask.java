package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for a reusable cooperative task.
 *
 * <p>The concrete task owns its state machine. This base class owns only the
 * execution lifecycle that would otherwise be repeated in every task:
 * starting one run, remembering its Future, cancellation and completion.</p>
 *
 * <p>{@code currentRunFuture} is execution infrastructure, not task/domain
 * state. It is therefore private here instead of being repeated in every
 * concrete task.</p>
 */
public abstract class AbstractTask implements CooperativeTask {

    /** Completion returned by the runner for the currently active run. */
    private CompletableFuture<Void> currentRunFuture;

    /**
     * Starts this task when no earlier run is still active.
     *
     * <p>{@link #resetForRun()} resets only the concrete task state. The
     * The supplied runner owns how individual runStep() calls are scheduled.</p>
     *
     * @return false when this task already has a current run
     */
    public final synchronized boolean start(CooperativeTaskRunner taskRunner) {
        if (taskRunner == null) {
            throw new IllegalArgumentException("taskRunner must not be null");
        }
        if (isRunning()) {
            return false;
        }

        resetForRun();

        currentRunFuture = taskRunner.runTask(this);

        /*
         * whenComplete is only the bridge from execution infrastructure back to
         * this task lifecycle. The named method keeps completion behaviour out
         * of an inline lambda.
         */
        currentRunFuture.whenComplete(this::runCompleted);
        return true;
    }

    /** Cancels the current run when one exists. */
    public final synchronized void cancel() {
        if (currentRunFuture != null) {
            currentRunFuture.cancel(true);
        }
    }

    /**
     * Returns whether this task still owns a current run.
     *
     * <p>The Future is cleared by runCompleted(). A new run therefore cannot be
     * admitted while completion of the previous run is still being processed.</p>
     */
    public final synchronized boolean isRunning() {
        return currentRunFuture != null;
    }

    /** Resets concrete state immediately before a new run starts. */
    protected abstract void resetForRun();

    /**
     * Optional concrete-task completion hook.
     *
     * <p>Cancellation is handled by this base class and is not reported here.</p>
     */
    protected void onRunCompleted(Throwable failure) {
        // Most reusable tasks need no completion action.
    }

    /**
     * Completes the execution lifecycle of one run.
     *
     * <p>The Future is cleared before the concrete completion hook runs. A
     * concrete task may therefore start a follow-up run from onRunCompleted().</p>
     */
    private void runCompleted(Void ignored, Throwable failure) {
        synchronized (this) {
            currentRunFuture = null;
        }

        if (failure instanceof CancellationException) {
            return;
        }

        onRunCompleted(failure);
    }
}
