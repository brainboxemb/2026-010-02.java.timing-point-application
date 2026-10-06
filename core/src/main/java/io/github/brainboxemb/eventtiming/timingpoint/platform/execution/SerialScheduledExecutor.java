package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One serial execution lane that supports immediate, one-shot delayed and
 * fixed-delay work.
 *
 * <p>Like {@link SerialExecutor}, this is primarily a logical lane rather than
 * necessarily a Java thread. In production, multiple TagProcessor lanes share
 * one runtime-owned scheduled role worker. Each lane still serializes its own
 * immediate work and periodic housekeeping.</p>
 *
 * <p>The scheduled worker is supplied from outside this lane. This class may
 * schedule timer triggers on it, but never creates, configures or shuts down the
 * underlying worker.</p>
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
     * Handle owned by the caller for one delayed or fixed-delay registration.
     *
     * <p>Closing the handle cancels future triggers but does not close this lane
     * or the shared runtime worker.</p>
     */
    public interface ScheduledRegistration extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * One delayed result-bearing registration.
     *
     * <p>The Future controls the actual lane work, including interruption when
     * cancellation races with a running provider call. The completion stage is
     * notification-only and lets callers compose follow-up work without
     * blocking a worker.</p>
     */
    public interface ScheduledResult<R>
            extends ScheduledRegistration {
        Future<R> futureResult();

        CompletionStage<R> completion();
    }

    /** Diagnostic identity of this logical scheduled lane. */
    private final String laneName;

    /** Capacity of the lane-local immediate/control queue. */
    private final int laneCapacity;

    /**
     * Externally owned scheduled worker used for timer triggers and drain work.
     */
    private final ScheduledExecutorService workerExecutor;

    private final SerialScheduledExecutorMetrics metrics;

    /** Active one-shot delayed registrations owned by this lane. */
    private final List<DelayedFutureTask<?>> delayedTasks =
            new ArrayList<DelayedFutureTask<?>>();

    /** Active fixed-delay registrations owned by this lane. */
    private final List<PeriodicTask> periodicTasks =
            new ArrayList<PeriodicTask>();

    private State state = State.NEW;

    /**
     * Lane-local serialization adapter. Immediate and periodic work both enter
     * this same lane.
     */
    private SerialExecutor lane;

    /** First fatal lane/scheduling failure, if any. */
    private Throwable failure;

    /**
     * Creates a logical scheduled lane on an externally owned scheduled worker.
     */
    public SerialScheduledExecutor(
            int laneCapacity,
            String laneName,
            ScheduledExecutorService workerExecutor) {
        if (laneCapacity < 1) {
            throw new IllegalArgumentException(
                    "laneCapacity must be positive");
        }
        if (laneName == null || laneName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "laneName must not be blank");
        }
        if (workerExecutor == null) {
            throw new IllegalArgumentException(
                    "workerExecutor must not be null");
        }

        this.laneName = laneName.trim();
        this.laneCapacity = laneCapacity;
        this.workerExecutor = workerExecutor;
        metrics = new SerialScheduledExecutorMetrics(
                this::metricQueueDepth);
    }

    /** Activates this logical lane on the supplied worker. */
    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialScheduledExecutor can only start from NEW; current state="
                            + state);
        }

        lane = new SerialExecutor(
                laneCapacity,
                laneName,
                workerExecutor);
        lane.start();
        state = State.RUNNING;
    }

    /**
     * Submits result-bearing work to this same serial lane.
     *
     * <p>Immediate, result-bearing and scheduled callbacks therefore share one
     * ordering boundary. Callers do not need a second executor merely because
     * one control operation has a return/failure path.</p>
     */
    public <R> SerialExecutor.SubmitResult<R> submit(
            Callable<R> work) {
        if (work == null) {
            throw new IllegalArgumentException(
                    "work must not be null");
        }

        final SerialExecutor activeLane;
        synchronized (this) {
            if (state != State.RUNNING) {
                metrics.recordImmediateRejected();
                return new SerialExecutor.SubmitResult<R>(
                        SerialExecutor.AdmissionResult.NOT_RUNNING,
                        null);
            }
            activeLane = lane;
        }

        SerialExecutor.SubmitResult<R> result =
                activeLane.submit(work);
        if (result.admission()
                == SerialExecutor.AdmissionResult.ACCEPTED) {
            metrics.recordImmediateAccepted();
        } else {
            metrics.recordImmediateRejected();
        }
        return result;
    }

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

        SerialExecutor activeLane;
        synchronized (this) {
            if (state != State.RUNNING) {
                metrics.recordImmediateRejected();
                return false;
            }
            activeLane = lane;
        }

        SerialExecutor.AdmissionResult admission =
                activeLane.offer(wrapImmediate(task));
        if (admission == SerialExecutor.AdmissionResult.ACCEPTED) {
            metrics.recordImmediateAccepted();
            return true;
        }
        metrics.recordImmediateRejected();
        return false;
    }

    /**
     * Schedules one delayed callback on this same logical serial lane.
     */
    public ScheduledRegistration schedule(
            Runnable task,
            long delayNanos) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }

        return submitAfter(
                () -> {
                    task.run();
                    return null;
                },
                delayNanos);
    }

    /**
     * Schedules one delayed result-bearing operation on this serial lane.
     *
     * <p>The timer registration itself never occupies the physical worker.
     * When due, the FutureTask is admitted to the ordinary serial lane. Calling
     * {@link Future#cancel(boolean)} with {@code true} can therefore interrupt
     * a provider call that is already running on the shared worker.</p>
     */
    public <R> ScheduledResult<R> submitAfter(
            Callable<R> work,
            long delayNanos) {
        if (work == null) {
            throw new IllegalArgumentException(
                    "work must not be null");
        }
        if (delayNanos < 0L) {
            throw new IllegalArgumentException(
                    "delayNanos must not be negative");
        }

        final DelayedFutureTask<R> delayed;
        synchronized (this) {
            if (state != State.RUNNING) {
                throw new IllegalStateException(
                        "SerialScheduledExecutor is not running");
            }
            delayed =
                    new DelayedFutureTask<R>(
                            work,
                            delayNanos);
            delayedTasks.add(delayed);
            metrics.recordScheduledRegistration();
        }
        delayed.schedule();
        return delayed;
    }

    /**
     * Registers fixed-delay work on this serial lane.
     *
     * <p>The timer trigger is scheduled on the supplied scheduler, while the
     * actual callback is admitted to the same serial lane as immediate work.
     * The next trigger is scheduled only after that callback finishes.</p>
     */
    public ScheduledRegistration scheduleWithFixedDelay(
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

        final PeriodicTask periodic;
        synchronized (this) {
            if (state != State.RUNNING) {
                throw new IllegalStateException(
                        "SerialScheduledExecutor is not running");
            }
            periodic = new PeriodicTask(task, delayNanos);
            periodicTasks.add(periodic);
            metrics.recordScheduledRegistration();
        }
        periodic.scheduleNext();
        return periodic;
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

    /** Supplies lane-local queue depth to the separate metrics object. */
    private synchronized int metricQueueDepth() {
        return lane == null
                ? 0
                : lane.metrics().snapshot().queueDepth();
    }

    /**
     * Cancels this lane's periodic registrations and closes the logical lane.
     *
     * <p>The externally owned scheduled worker remains running.</p>
     */
    @Override
    public void close() {
        final SerialExecutor activeLane;
        final List<DelayedFutureTask<?>> delayed;
        final List<PeriodicTask> tasks;

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

            activeLane = lane;
            delayed = new ArrayList<DelayedFutureTask<?>>(delayedTasks);
            tasks = new ArrayList<PeriodicTask>(periodicTasks);
        }

        for (DelayedFutureTask<?> task : delayed) {
            task.close();
        }
        for (PeriodicTask periodic : tasks) {
            periodic.close();
        }

        if (activeLane != null) {
            activeLane.close();
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
        List<DelayedFutureTask<?>> delayed;
        List<PeriodicTask> tasks;
        synchronized (this) {
            if (state == State.FAILED) {
                return;
            }
            failure = cause;
            state = State.FAILED;
            delayed = new ArrayList<DelayedFutureTask<?>>(delayedTasks);
            tasks = new ArrayList<PeriodicTask>(periodicTasks);
        }

        for (DelayedFutureTask<?> task : delayed) {
            task.close();
        }
        for (PeriodicTask periodic : tasks) {
            periodic.close();
        }
    }

    /**
     * One result-bearing task that is first delayed and then enters the serial lane.
     */
    private final class DelayedFutureTask<R>
            extends FutureTask<R>
            implements ScheduledResult<R> {
        private final long delayNanos;
        private final CompletableFuture<R> completion =
                new CompletableFuture<R>();

        private volatile boolean executionStarted;
        private ScheduledFuture<?> trigger;

        private DelayedFutureTask(
                Callable<R> work,
                long delayNanos) {
            super(work);
            this.delayNanos = delayNanos;
        }

        private void schedule() {
            if (isDone()) {
                return;
            }
            synchronized (SerialScheduledExecutor.this) {
                if (state != State.RUNNING) {
                    cancel(false);
                    return;
                }
            }

            try {
                ScheduledFuture<?> next =
                        workerExecutor.schedule(
                                this::enqueueExecution,
                                delayNanos,
                                TimeUnit.NANOSECONDS);
                synchronized (this) {
                    if (isDone()) {
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
                completion.completeExceptionally(ex);
                cancel(false);
            }
        }

        private void enqueueExecution() {
            synchronized (this) {
                trigger = null;
            }
            if (isDone()) {
                return;
            }

            final SerialExecutor activeLane;
            synchronized (SerialScheduledExecutor.this) {
                if (state != State.RUNNING) {
                    cancel(false);
                    return;
                }
                activeLane = lane;
            }

            SerialExecutor.AdmissionResult admission =
                    activeLane.offer(this);

            if (admission
                    != SerialExecutor.AdmissionResult.ACCEPTED) {
                schedule();
            }
        }

        @Override
        public void run() {
            executionStarted = true;
            super.run();
        }

        @Override
        protected void done() {
            synchronized (SerialScheduledExecutor.this) {
                delayedTasks.remove(this);
            }
            if (executionStarted) {
                metrics.recordDelayedExecution();
            }

            if (isCancelled()) {
                completion.cancel(false);
                return;
            }

            try {
                completion.complete(get());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                completion.completeExceptionally(ex);
            } catch (ExecutionException ex) {
                Throwable cause =
                        ex.getCause() == null
                                ? ex
                                : ex.getCause();
                metrics.recordRuntimeFailure();
                completion.completeExceptionally(cause);

                if (cause instanceof Error) {
                    markFailed(
                            (Error) cause);
                } else {
                    LOG.warn(
                            "Serial scheduled lane {} delayed task failed",
                            laneName,
                            cause);
                }
            }
        }

        @Override
        public boolean cancel(
                boolean mayInterruptIfRunning) {
            ScheduledFuture<?> pending;
            synchronized (this) {
                pending = trigger;
                trigger = null;
            }
            if (pending != null) {
                pending.cancel(false);
            }
            return super.cancel(
                    mayInterruptIfRunning);
        }

        @Override
        public Future<R> futureResult() {
            return this;
        }

        @Override
        public CompletionStage<R> completion() {
            return completion;
        }

        @Override
        public void close() {
            if (cancel(false)) {
                metrics.recordScheduledCancellation();
            }
        }
    }

    /**
     * One logical fixed-delay registration.
     *
     * <p>{@code trigger} is only the next timer wake-up on the supplied scheduler.
     * The actual user callback is serialized through {@link #lane}.</p>
     */
    private final class PeriodicTask
            implements ScheduledRegistration {
        private final Runnable task;
        private final long delayNanos;

        private boolean closed;
        private ScheduledFuture<?> trigger;

        private PeriodicTask(
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
                        workerExecutor.schedule(
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

            SerialExecutor activeLane;
            synchronized (SerialScheduledExecutor.this) {
                if (state != State.RUNNING) {
                    return;
                }
                activeLane = SerialScheduledExecutor.this.lane;
            }

            SerialExecutor.AdmissionResult admission =
                    activeLane.offer(() -> {
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
                periodicTasks.remove(this);
            }
            if (recordCancellation) {
                metrics.recordScheduledCancellation();
            }
        }
    }
}
