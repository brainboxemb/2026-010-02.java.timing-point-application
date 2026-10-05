package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One serial execution lane that supports immediate and fixed-delay work.
 *
 * <p>Like {@link SerialExecutor}, this is primarily a logical lane rather than
 * necessarily a Java thread. In production, multiple TagProcessor lanes share
 * one runtime-owned scheduled role worker. Each lane still serializes its own
 * immediate work and periodic housekeeping.</p>
 *
 * <p>The one-argument constructor is a standalone convenience and creates one
 * private scheduled worker in {@link #start()}. The three-argument constructor
 * receives a runtime-owned shared worker. This lane may schedule work on that
 * worker but never owns or shuts down its lifecycle.</p>
 */
public final class SerialScheduledExecutor implements AutoCloseable {
    private static final Logger LOG =
            LoggerFactory.getLogger(SerialScheduledExecutor.class);

    /** Lifecycle of this logical scheduled lane. */
    public enum State {
        NEW,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    /**
     * Handle owned by the caller for one fixed-delay registration.
     *
     * <p>Closing the handle cancels future triggers but does not close this lane
     * or the shared runtime worker.</p>
     */
    public interface ScheduledTask extends AutoCloseable {
        @Override
        void close();
    }

    /** Diagnostic identity of this logical scheduled lane. */
    private final String laneName;

    /** Capacity of the lane-local immediate/control queue in shared-worker mode. */
    private final int sharedLaneCapacity;

    /**
     * Runtime-owned scheduled role worker.
     *
     * <p>{@code null} means standalone mode. A non-null value means the worker
     * is owned by RuntimeExecutors and must never be shut down here.</p>
     */
    private final ScheduledExecutorService sharedWorkerExecutor;

    private final SerialScheduledExecutorMetrics metrics;

    /** Active fixed-delay registrations owned by this lane in shared-worker mode. */
    private final List<SharedPeriodicTask> sharedPeriodicTasks =
            new ArrayList<SharedPeriodicTask>();

    private State state = State.NEW;

    /** Private physical worker used only by the standalone constructor. */
    private ScheduledThreadPoolExecutor standaloneExecutor;

    /**
     * Lane-local serialization adapter used only with a shared role worker.
     * Immediate and periodic work both enter this same lane.
     */
    private SerialExecutor sharedLane;

    /** First fatal lane/scheduling failure, if any. */
    private Throwable failure;

    /** Standalone serial scheduled lane with a private worker. */
    public SerialScheduledExecutor(String threadName) {
        if (threadName == null || threadName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "threadName must not be blank");
        }
        laneName = threadName.trim();
        sharedLaneCapacity = 0;
        sharedWorkerExecutor = null;
        metrics = new SerialScheduledExecutorMetrics(
                this::metricQueueDepth,
                true);
    }

    /**
     * Logical scheduled lane serviced by a runtime-owned shared role executor.
     */
    public SerialScheduledExecutor(
            int laneCapacity,
            String laneName,
            ScheduledExecutorService sharedExecutor) {
        if (laneCapacity < 1) {
            throw new IllegalArgumentException(
                    "laneCapacity must be positive");
        }
        if (laneName == null || laneName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "laneName must not be blank");
        }
        if (sharedExecutor == null) {
            throw new IllegalArgumentException(
                    "sharedExecutor must not be null");
        }

        this.laneName = laneName.trim();
        this.sharedLaneCapacity = laneCapacity;
        this.sharedWorkerExecutor = sharedExecutor;
        metrics = new SerialScheduledExecutorMetrics(
                this::metricQueueDepth,
                false);
    }

    /**
     * Shared mode is represented by the presence of the runtime-owned worker
     * dependency; no second lifecycle/ownership flag is kept.
     */
    private boolean usesSharedWorker() {
        return sharedWorkerExecutor != null;
    }

    /**
     * Activates this lane.
     *
     * <p>Shared mode creates only the lane-local serialization adapter. It does
     * not create another physical worker. Standalone mode creates one private
     * scheduled worker.</p>
     */
    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialScheduledExecutor can only start from NEW; current state="
                            + state);
        }

        if (usesSharedWorker()) {
            sharedLane = new SerialExecutor(
                    sharedLaneCapacity,
                    laneName,
                    sharedWorkerExecutor);
            sharedLane.start();
        } else {
            ThreadFactory threadFactory = runnable -> {
                Thread thread =
                        new Thread(runnable, laneName);
                metrics.recordStandaloneWorkerThread(thread);
                return thread;
            };
            standaloneExecutor =
                    new ScheduledThreadPoolExecutor(
                            1,
                            threadFactory);
            standaloneExecutor.setRemoveOnCancelPolicy(true);
            standaloneExecutor
                    .setExecuteExistingDelayedTasksAfterShutdownPolicy(
                            true);
            standaloneExecutor
                    .setContinueExistingPeriodicTasksAfterShutdownPolicy(
                            false);
            standaloneExecutor.prestartCoreThread();
        }

        state = State.RUNNING;
    }

    /**
     * Queues one immediate operation on this logical serial lane.
     *
     * @return false when this lane no longer accepts work
     */
    /**
     * Attempts to admit immediate work to this serial lane.
     *
     * @return {@code false} when the lane is not running or cannot accept the work
     */
    public boolean execute(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }

        if (usesSharedWorker()) {
            SerialExecutor lane;
            synchronized (this) {
                if (state != State.RUNNING) {
                    metrics.recordImmediateRejected();
                    return false;
                }
                lane = sharedLane;
            }

            SerialExecutor.AdmissionResult admission =
                    lane.offer(wrapImmediate(task));
            if (admission
                    == SerialExecutor.AdmissionResult.ACCEPTED) {
                metrics.recordImmediateAccepted();
                return true;
            }
            metrics.recordImmediateRejected();
            return false;
        }

        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state != State.RUNNING) {
                metrics.recordImmediateRejected();
                return false;
            }
            active = standaloneExecutor;
        }

        try {
            active.execute(wrapImmediate(task));
            metrics.recordImmediateAccepted();
            return true;
        } catch (RejectedExecutionException ex) {
            metrics.recordImmediateRejected();
            return false;
        }
    }

    /**
     * Registers fixed-delay work on this serial lane.
     *
     * <p>In shared mode the timer trigger is scheduled on the runtime worker,
     * but the actual callback is first admitted to {@link #sharedLane}; periodic
     * state therefore never runs concurrently with immediate work from the same
     * lane. The next trigger is scheduled only after that callback finishes.</p>
     */
    public ScheduledTask scheduleWithFixedDelay(
            Runnable task,
            long delayNanos) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }
        if (delayNanos < 1L) {
            throw new IllegalArgumentException(
                    "delayNanos must be positive");
        }

        if (usesSharedWorker()) {
            final SharedPeriodicTask periodic;
            synchronized (this) {
                if (state != State.RUNNING) {
                    throw new IllegalStateException(
                            "SerialScheduledExecutor is not running");
                }
                periodic =
                        new SharedPeriodicTask(
                                task,
                                delayNanos);
                sharedPeriodicTasks.add(periodic);
                metrics.recordScheduledRegistration();
            }
            periodic.scheduleNext();
            return periodic;
        }

        ScheduledThreadPoolExecutor active;
        synchronized (this) {
            if (state != State.RUNNING) {
                throw new IllegalStateException(
                        "SerialScheduledExecutor is not running");
            }
            active = standaloneExecutor;
        }

        try {
            ScheduledFuture<?> future =
                    active.scheduleWithFixedDelay(
                            wrapPeriodic(task),
                            delayNanos,
                            delayNanos,
                            TimeUnit.NANOSECONDS);
            metrics.recordScheduledRegistration();
            return () -> {
                if (future.cancel(false)) {
                    metrics.recordScheduledCancellation();
                }
            };
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

    /** Returns the separate pull-based metrics owner for this lane. */
    public SerialScheduledExecutorMetrics metrics() {
        return metrics;
    }

    /**
     * Supplies lane-local queue depth to the separate metrics object.
     */
    private synchronized int metricQueueDepth() {
        if (usesSharedWorker()) {
            return sharedLane == null
                    ? 0
                    : sharedLane.metrics().snapshot().queueDepth();
        }
        return standaloneExecutor == null
                ? 0
                : standaloneExecutor.getQueue().size();
    }

    /**
     * Cancels this lane's periodic registrations and stops its local execution.
     *
     * <p>Standalone mode also stops its private scheduled worker. Shared mode
     * closes only the lane-local serial adapter; Runtime remains responsible for
     * the physical shared worker.</p>
     */
    @Override
    public void close() {
        final SerialExecutor lane;
        final ScheduledThreadPoolExecutor standalone;
        final List<SharedPeriodicTask> periodicTasks;

        synchronized (this) {
            if (state == State.NEW) {
                state = State.STOPPED;
                return;
            }
            if (state == State.RUNNING) {
                state = State.STOPPING;
            }
            if (state == State.STOPPED) {
                return;
            }

            lane = sharedLane;
            standalone = standaloneExecutor;
            periodicTasks =
                    new ArrayList<SharedPeriodicTask>(
                            sharedPeriodicTasks);
        }

        for (SharedPeriodicTask periodic : periodicTasks) {
            periodic.close();
        }

        if (usesSharedWorker()) {
            if (lane != null) {
                lane.close();
            }
        } else if (standalone != null) {
            standalone.shutdown();
            awaitStandaloneTermination(standalone);
        }

        synchronized (this) {
            if (state != State.FAILED) {
                state = State.STOPPED;
            }
        }
    }

    private static void awaitStandaloneTermination(
            ScheduledThreadPoolExecutor executor) {
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(
                        100L,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Runnable wrapImmediate(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                metrics.recordRuntimeFailure();
                LOG.warn(
                        "Serial scheduled lane {} task failed",
                        laneName,
                        ex);
            } catch (Error ex) {
                metrics.recordRuntimeFailure();
                markFailed(ex);
                throw ex;
            } finally {
                metrics.recordImmediateExecution();
            }
        };
    }

    private Runnable wrapPeriodic(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException ex) {
                metrics.recordRuntimeFailure();
                LOG.warn(
                        "Serial scheduled lane {} periodic task failed",
                        laneName,
                        ex);
            } catch (Error ex) {
                metrics.recordRuntimeFailure();
                markFailed(ex);
                throw ex;
            } finally {
                metrics.recordPeriodicExecution();
            }
        };
    }

    private void markFailed(Error cause) {
        ScheduledThreadPoolExecutor standalone = null;
        List<SharedPeriodicTask> periodicTasks;
        synchronized (this) {
            if (state == State.FAILED) {
                return;
            }
            failure = cause;
            state = State.FAILED;
            periodicTasks =
                    new ArrayList<SharedPeriodicTask>(
                            sharedPeriodicTasks);
            if (!usesSharedWorker()) {
                standalone = standaloneExecutor;
            }
        }

        for (SharedPeriodicTask periodic : periodicTasks) {
            periodic.close();
        }

        /*
         * A shared lane failure is isolated to this lane. Never stop the
         * runtime-owned TagProcessor role executor from component code.
         */
        if (standalone != null) {
            standalone.shutdownNow();
        }
    }

    /**
     * One logical fixed-delay registration in shared-worker mode.
     *
     * <p>{@code trigger} is only the next timer wake-up on the shared scheduler.
     * The actual user callback is serialized through {@link #sharedLane}.</p>
     */
    private final class SharedPeriodicTask
            implements ScheduledTask {
        private final Runnable task;
        private final long delayNanos;

        private boolean closed;
        private ScheduledFuture<?> trigger;

        private SharedPeriodicTask(
                Runnable task,
                long delayNanos) {
            this.task = task;
            this.delayNanos = delayNanos;
        }

        private void scheduleNext() {
            synchronized (this) {
                if (closed) {
                    return;
                }
            }
            synchronized (SerialScheduledExecutor.this) {
                if (state != State.RUNNING) {
                    return;
                }
            }

            try {
                ScheduledFuture<?> next =
                        sharedWorkerExecutor.schedule(
                                this::enqueueExecution,
                                delayNanos,
                                TimeUnit.NANOSECONDS);
                synchronized (this) {
                    if (closed) {
                        next.cancel(false);
                    } else {
                        trigger = next;
                    }
                }
            } catch (RejectedExecutionException ex) {
                synchronized (SerialScheduledExecutor.this) {
                    if (state == State.RUNNING) {
                        failure = ex;
                        state = State.FAILED;
                    }
                }
            }
        }

        private void enqueueExecution() {
            synchronized (this) {
                trigger = null;
                if (closed) {
                    return;
                }
            }

            SerialExecutor lane;
            synchronized (SerialScheduledExecutor.this) {
                if (state != State.RUNNING) {
                    return;
                }
                lane = sharedLane;
            }

            SerialExecutor.AdmissionResult admission =
                    lane.offer(() -> {
                        try {
                            wrapPeriodic(task).run();
                        } finally {
                            scheduleNext();
                        }
                    });

            if (admission
                    != SerialExecutor.AdmissionResult.ACCEPTED) {
                /*
                 * This is control/scheduling work, not TagObservation ingress.
                 * Retry after the normal fixed delay rather than creating an
                 * unbounded second queue.
                 */
                scheduleNext();
            }
        }

        @Override
        public void close() {
            ScheduledFuture<?> pending;
            boolean recordCancellation = false;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pending = trigger;
                trigger = null;
                recordCancellation = true;
            }

            if (pending != null) {
                pending.cancel(false);
            }

            synchronized (SerialScheduledExecutor.this) {
                sharedPeriodicTasks.remove(this);
            }
            if (recordCancellation) {
                metrics.recordScheduledCancellation();
            }
        }
    }
}
