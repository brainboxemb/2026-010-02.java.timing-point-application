package io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks;



import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Base class for a reusable yielding task.
 *
 * <p>The concrete task owns its operation/control state. This base class owns
 * execution lifecycle: starting a run, remembering whether it is active,
 * coalescing wake requests, cancellation and completion.</p>
 */
public abstract class AbstractTask implements YieldingTask {

    private CompletableFuture<Void> currentRunFuture;
    private SerialTaskRunner currentLane;
    private boolean wakePending;

    /**
     * Starts this task when no earlier run is still active.
     *
     * <p>This is the ordinary operation-task entry point. A new run resets the
     * concrete task state through {@link #resetForRun()}.</p>
     *
     * @return false when this task already has a current run
     */
    public final synchronized boolean start(SerialTaskRunner taskLane) {
        checkLane(taskLane);
        if (isRunning()) {
            return false;
        }

        wakePending = false;
        startRun(taskLane);
        return true;
    }

    /**
     * Requests a run of this task.
     *
     * <p>If a run is already active, repeated wake requests collapse into one
     * follow-up run after the current run completes successfully.</p>
     */
    public final void wake(SerialTaskRunner taskLane) {
        checkLane(taskLane);

        synchronized (this) {
            if (isRunning()) {
                wakePending = true;
                return;
            }

            wakePending = false;
            try {
                startRun(taskLane);
                return;
            } catch (RuntimeException ex) {
                completeFailedWake(ex);
                return;
            } catch (Error error) {
                completeFailedWake(error);
                throw error;
            }
        }
    }

    /** Cancels the current run and drops any remembered follow-up wake. */
    public final synchronized void cancel() {
        wakePending = false;
        if (currentRunFuture != null) {
            currentRunFuture.cancel(true);
        }
    }

    /** Returns whether this task still owns a current run. */
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

    private void startRun(SerialTaskRunner taskLane) {
        resetForRun();
        currentLane = taskLane;
        currentRunFuture = taskLane.runTask(this);
        currentRunFuture.whenComplete(this::runCompleted);
    }

    private void runCompleted(Void ignored, Throwable failure) {
        final SerialTaskRunner restartLane;

        synchronized (this) {
            currentRunFuture = null;

            if (failure == null && wakePending) {
                wakePending = false;
                restartLane = currentLane;
            } else {
                restartLane = null;
            }
        }

        if (!(failure instanceof CancellationException)) {
            onRunCompleted(failure);
        }

        if (restartLane != null) {
            wake(restartLane);
        }
    }

    private void completeFailedWake(Throwable failure) {
        currentRunFuture = null;
        onRunCompleted(failure);
    }

    private static void checkLane(SerialTaskRunner taskLane) {
        if (taskLane == null) {
            throw new IllegalArgumentException("taskLane must not be null");
        }
    }
}
