package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One JDK-backed serial execution lane with delayed/fixed-delay scheduling.
 *
 * <p>This executor does not own a domain input queue. Components such as
 * TagProcessor keep their bounded input data separately and use this type only
 * for coalesced execution and scheduled housekeeping.</p>
 */
public final class SerialScheduledExecutor implements AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(SerialScheduledExecutor.class);

    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public interface ScheduledTask extends AutoCloseable {
        @Override
        void close();
    }

    private final String threadName;

    private State state = State.NEW;
    private ScheduledThreadPoolExecutor executor;
    private Throwable failure;

    public SerialScheduledExecutor(String threadName) {
        if (threadName == null || threadName.trim().isEmpty()) {
            throw new IllegalArgumentException("threadName must not be blank");
        }
        this.threadName = threadName.trim();
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialScheduledExecutor can only start from NEW; current state="
                            + state);
        }

        ThreadFactory threadFactory =
                runnable -> new Thread(runnable, threadName);
        executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        /*
         * Immediate execute(...) work is represented by zero-delay scheduled
         * tasks in ScheduledThreadPoolExecutor. Keep already accepted immediate
         * drain work executable during graceful shutdown. Periodic work is
         * cancelled separately by its owner and must not continue after shutdown.
         */
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        state = State.RUNNING;
        executor.prestartCoreThread();
    }

    /**
     * Queues one immediate operation on the serial lane.
     *
     * @return false when the executor no longer accepts work
     */
    public boolean execute(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }

        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state != State.RUNNING) {
                return false;
            }
            active = executor;
        }

        try {
            active.execute(wrapImmediate(task));
            return true;
        } catch (RejectedExecutionException ex) {
            return false;
        }
    }

    public ScheduledTask scheduleWithFixedDelay(
            Runnable task,
            long delayNanos) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        if (delayNanos < 1L) {
            throw new IllegalArgumentException("delayNanos must be positive");
        }

        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state != State.RUNNING) {
                throw new IllegalStateException(
                        "SerialScheduledExecutor is not running");
            }
            active = executor;
        }

        try {
            ScheduledFuture<?> future = active.scheduleWithFixedDelay(
                    wrapPeriodic(task),
                    delayNanos,
                    delayNanos,
                    TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException ex) {
            throw new IllegalStateException(
                    "SerialScheduledExecutor stopped while scheduling work",
                    ex);
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Throwable failure() {
        return failure;
    }

    @Override
    public void close() {
        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state == State.NEW) {
                state = State.STOPPED;
                return;
            }
            if (state == State.RUNNING) {
                state = State.STOPPING;
            }
            if (state == State.STOPPED || state == State.FAILED) {
                return;
            }
            active = executor;
        }

        active.shutdown();
        boolean interrupted = false;
        while (!active.isTerminated()) {
            try {
                active.awaitTermination(100L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        synchronized (this) {
            if (state != State.FAILED) {
                state = State.STOPPED;
            }
        }
    }

    private Runnable wrapImmediate(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                LOG.warn("Serial scheduled executor task failed", ex);
            } catch (Error ex) {
                markFailed(ex);
                throw ex;
            }
        };
    }

    private Runnable wrapPeriodic(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                /*
                 * ScheduledThreadPoolExecutor suppresses later fixed-delay
                 * executions when a task escapes with an exception. Keep the
                 * lane alive and make the failure visible instead.
                 */
                LOG.warn("Serial scheduled periodic task failed", ex);
            } catch (Error ex) {
                markFailed(ex);
                throw ex;
            }
        };
    }

    private void markFailed(Error cause) {
        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state == State.FAILED) {
                return;
            }
            failure = cause;
            state = State.FAILED;
            active = executor;
        }
        if (active != null) {
            active.shutdownNow();
        }
    }
}
