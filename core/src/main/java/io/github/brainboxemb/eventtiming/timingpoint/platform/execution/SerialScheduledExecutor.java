package io.github.brainboxemb.eventtiming.timingpoint.platform.execution;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
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
 * One serial execution lane with delayed/fixed-delay scheduling.
 *
 * <p>The standalone constructor owns one JDK scheduled worker. Production
 * runtime composition may instead provide a shared scheduled role executor.
 * In shared mode immediate and periodic work still enters one lane-local serial
 * queue, while the physical worker is shared by all TagProcessor lanes.</p>
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

    /** Lane-local engineering metrics. */
    public static final class Metrics {
        private final SerialScheduledExecutor owner;

        private long immediateAcceptedCount;
        private long immediateRejectedCount;
        private long scheduledRegistrationCount;
        private long scheduledCancellationCount;
        private volatile long immediateExecutionCount;
        private volatile long periodicExecutionCount;
        private volatile long runtimeFailureCount;
        private volatile Thread standaloneWorkerThread;

        private Metrics(SerialScheduledExecutor owner) {
            this.owner = owner;
        }

        private void recordStandaloneWorkerThread(Thread thread) {
            standaloneWorkerThread = thread;
        }

        private void recordImmediateAccepted() {
            synchronized (owner) {
                immediateAcceptedCount++;
            }
        }

        private void recordImmediateRejected() {
            synchronized (owner) {
                immediateRejectedCount++;
            }
        }

        private void recordScheduledRegistration() {
            synchronized (owner) {
                scheduledRegistrationCount++;
            }
        }

        private void recordScheduledCancellation() {
            synchronized (owner) {
                scheduledCancellationCount++;
            }
        }

        private void recordImmediateExecution() {
            immediateExecutionCount++;
        }

        private void recordPeriodicExecution() {
            periodicExecutionCount++;
        }

        private void recordRuntimeFailure() {
            runtimeFailureCount++;
        }

        public Snapshot snapshot() {
            int queueDepth;
            long immediateAccepted;
            long immediateRejected;
            long scheduledRegistrations;
            long scheduledCancellations;

            synchronized (owner) {
                if (owner.sharedMode
                        && owner.sharedLane != null) {
                    queueDepth = owner.sharedLane
                            .metrics()
                            .snapshot()
                            .queueDepth();
                } else {
                    ScheduledThreadPoolExecutor active =
                            owner.standaloneExecutor;
                    queueDepth = active == null
                            ? 0
                            : active.getQueue().size();
                }
                immediateAccepted = immediateAcceptedCount;
                immediateRejected = immediateRejectedCount;
                scheduledRegistrations =
                        scheduledRegistrationCount;
                scheduledCancellations =
                        scheduledCancellationCount;
            }

            return new Snapshot(
                    queueDepth,
                    immediateAccepted,
                    immediateRejected,
                    scheduledRegistrations,
                    scheduledCancellations,
                    immediateExecutionCount,
                    periodicExecutionCount,
                    runtimeFailureCount,
                    workerThreadCpuTimeNanos());
        }

        private long workerThreadCpuTimeNanos() {
            if (owner.sharedMode) {
                return -1L;
            }
            Thread worker = standaloneWorkerThread;
            if (worker == null) {
                return -1L;
            }

            ThreadMXBean bean =
                    ManagementFactory.getThreadMXBean();
            if (!bean.isThreadCpuTimeSupported()
                    || !bean.isThreadCpuTimeEnabled()) {
                return -1L;
            }
            long cpuTime =
                    bean.getThreadCpuTime(worker.getId());
            return cpuTime < 0L ? -1L : cpuTime;
        }

        public static final class Snapshot {
            private final int queueDepth;
            private final long immediateAcceptedCount;
            private final long immediateRejectedCount;
            private final long scheduledRegistrationCount;
            private final long scheduledCancellationCount;
            private final long immediateExecutionCount;
            private final long periodicExecutionCount;
            private final long runtimeFailureCount;
            private final long workerThreadCpuTimeNanos;

            private Snapshot(
                    int queueDepth,
                    long immediateAcceptedCount,
                    long immediateRejectedCount,
                    long scheduledRegistrationCount,
                    long scheduledCancellationCount,
                    long immediateExecutionCount,
                    long periodicExecutionCount,
                    long runtimeFailureCount,
                    long workerThreadCpuTimeNanos) {
                this.queueDepth = queueDepth;
                this.immediateAcceptedCount =
                        immediateAcceptedCount;
                this.immediateRejectedCount =
                        immediateRejectedCount;
                this.scheduledRegistrationCount =
                        scheduledRegistrationCount;
                this.scheduledCancellationCount =
                        scheduledCancellationCount;
                this.immediateExecutionCount =
                        immediateExecutionCount;
                this.periodicExecutionCount =
                        periodicExecutionCount;
                this.runtimeFailureCount =
                        runtimeFailureCount;
                this.workerThreadCpuTimeNanos =
                        workerThreadCpuTimeNanos;
            }

            public int queueDepth() {
                return queueDepth;
            }

            public long immediateAcceptedCount() {
                return immediateAcceptedCount;
            }

            public long immediateRejectedCount() {
                return immediateRejectedCount;
            }

            public long scheduledRegistrationCount() {
                return scheduledRegistrationCount;
            }

            public long scheduledCancellationCount() {
                return scheduledCancellationCount;
            }

            public long immediateExecutionCount() {
                return immediateExecutionCount;
            }

            public long periodicExecutionCount() {
                return periodicExecutionCount;
            }

            public long runtimeFailureCount() {
                return runtimeFailureCount;
            }

            public long workerThreadCpuTimeNanos() {
                return workerThreadCpuTimeNanos;
            }
        }
    }

    private final String laneName;
    private final int sharedLaneCapacity;
    private final ScheduledExecutorService suppliedSharedExecutor;
    private final boolean sharedMode;
    private final Metrics metrics = new Metrics(this);
    private final List<SharedPeriodicTask> sharedPeriodicTasks =
            new ArrayList<SharedPeriodicTask>();

    private State state = State.NEW;
    private ScheduledThreadPoolExecutor standaloneExecutor;
    private SerialExecutor sharedLane;
    private Throwable failure;

    /** Standalone serial scheduled lane with a private worker. */
    public SerialScheduledExecutor(String threadName) {
        if (threadName == null || threadName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "threadName must not be blank");
        }
        laneName = threadName.trim();
        sharedLaneCapacity = 0;
        suppliedSharedExecutor = null;
        sharedMode = false;
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
        this.suppliedSharedExecutor = sharedExecutor;
        sharedMode = true;
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException(
                    "SerialScheduledExecutor can only start from NEW; current state="
                            + state);
        }

        if (sharedMode) {
            sharedLane = new SerialExecutor(
                    sharedLaneCapacity,
                    laneName,
                    suppliedSharedExecutor);
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
    public boolean execute(Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException(
                    "task must not be null");
        }

        if (sharedMode) {
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

        if (sharedMode) {
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

    public Metrics metrics() {
        return metrics;
    }

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

        if (sharedMode) {
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
            if (!sharedMode) {
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
                        suppliedSharedExecutor.schedule(
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
