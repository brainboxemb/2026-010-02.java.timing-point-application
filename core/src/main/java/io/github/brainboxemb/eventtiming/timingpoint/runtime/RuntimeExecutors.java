package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeDataLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Central construction and lifecycle owner for runtime execution resources.
 *
 * <p>Logical serial lanes remain per TimingNode/TagProcessor, but backing
 * executors are shared by functional role. This keeps per-node FIFO/admission
 * state without multiplying Java threads as nodes are added on the Raspberry Pi
 * Zero baseline.</p>
 *
 * <p>Blocking I/O remains separate from the Domain processing executors. All
 * project-owned threads deliberately use the JVM default priority; correctness
 * and progress never depend on priority.</p>
 */
final class RuntimeExecutors implements AutoCloseable {
    static final int TIMING_NODE_DATA_LANE_QUEUE_CAPACITY = 32;
    static final int TIMING_NODE_DATA_LANE_MAX_ITEMS_PER_RUN = 32;
    static final int TIMING_NODE_TASK_LANE_QUEUE_CAPACITY = 32;
    static final int TIMING_NODE_TASK_LANE_MAX_ITEMS_PER_RUN = 32;
    static final int ANTENNA_CONTROL_LANE_QUEUE_CAPACITY = 8;
    static final int ANTENNA_CONTROL_LANE_MAX_ITEMS_PER_RUN = 8;
    static final int TIMING_SYSTEM_CONDUCTOR_LANE_QUEUE_CAPACITY = 8;
    static final int TIMING_SYSTEM_CONDUCTOR_LANE_MAX_ITEMS_PER_RUN = 8;

    /*
     * Step-5 baseline: one physical blocking-I/O thread.
     *
     * Additional physical I/O parallelism is a measurement-driven decision.
     * V01 must demonstrate a real bottleneck before this count is increased.
     */
    private static final int SHARED_IO_THREADS = 1;

    /** Node-local logical lanes resolved for one TimingNode. */
    static final class TimingNodeExecutionResources {
        private final TimingNodeDataLane dataLane;
        private final SerialTaskRunner taskRunner;
        private final ScheduledExecutorService tagProcessorScheduler;

        private TimingNodeExecutionResources(
                TimingNodeDataLane dataLane,
                SerialTaskRunner taskRunner,
                ScheduledExecutorService tagProcessorScheduler) {

            this.dataLane = dataLane;
            this.taskRunner = taskRunner;
            this.tagProcessorScheduler = tagProcessorScheduler;
        }

        TimingNodeDataLane dataLane() {

            return dataLane;
        }

        SerialTaskRunner taskRunner() {

            return taskRunner;
        }

        ScheduledExecutorService tagProcessorScheduler() {

            return tagProcessorScheduler;
        }
    }

    private final List<AutoCloseable> serialLanes = new ArrayList<AutoCloseable>();

    /*
     * Each serial lane has at most one queue worker run scheduled or executing
     * at a time, so these backing queues are bounded structurally by the number
     * of configured lanes rather than by registration volume. Registration/command
     * overload remains bounded and visible in the lane-local queues.
     */
    private final ScheduledThreadPoolExecutor timingNodeExecutor;
    private final ScheduledThreadPoolExecutor tagProcessorScheduledExecutor;

    /**
     * Shared executor for TimingSystemConductor lanes.
     *
     * <p>TimingSystemConductor work must not run synchronously on the emitting
     * Domain or I/O component thread. Logical TimingSystemConductor lanes therefore use
     * this separate executor.</p>
     */
    private final ScheduledThreadPoolExecutor timingSystemConductorExecutor;

    /**
     * Shared scheduled I/O executor. AntennaManager
     * needs delayed multiplex rotation, while normal provider calls use the same
     * executor for immediate tasks.
     */
    private final ScheduledThreadPoolExecutor sharedIoScheduledExecutor;

    private boolean started;
    private boolean closed;

    RuntimeExecutors() {

        timingNodeExecutor =
                new ScheduledThreadPoolExecutor(
                        1,
                        threadFactory("tp-dl-node"));
        timingNodeExecutor.setRemoveOnCancelPolicy(true);
        tagProcessorScheduledExecutor =
                new ScheduledThreadPoolExecutor(1, threadFactory("tp-dl-tagproc"));
        tagProcessorScheduledExecutor.setRemoveOnCancelPolicy(true);

        timingSystemConductorExecutor =
                new ScheduledThreadPoolExecutor(
                        1,
                        threadFactory("tp-dl-system-conductor"));
        timingSystemConductorExecutor.setRemoveOnCancelPolicy(true);

        sharedIoScheduledExecutor =
                new ScheduledThreadPoolExecutor(SHARED_IO_THREADS, threadFactory("tp-io-shared"));
        sharedIoScheduledExecutor.setRemoveOnCancelPolicy(true);
    }

    /**
     * Prestarts the Java threads owned by this Runtime's executors.
     *
     * <p>Construction deliberately does not start threads. Runtime composition
     * calls this method only after the complete object graph has been constructed
     * and application relationships have been wired.</p>
     */
    synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "RuntimeExecutors is already closed");
        }
        if (started) {
            return;
        }

        timingNodeExecutor.prestartAllCoreThreads();
        tagProcessorScheduledExecutor.prestartAllCoreThreads();
        timingSystemConductorExecutor.prestartAllCoreThreads();
        sharedIoScheduledExecutor.prestartAllCoreThreads();
        started = true;
    }

    /**
     * Creates one node-local execution-resource set on shared Runtime executors.
     *
     * <p>The typed Data Lane and cooperative Task Lane keep independent bounded queues and FIFO
     * ordering, but they deliberately share the same TimingNode executor.
     * TagProcessor receives the Runtime-owned single-thread scheduled executor
     * directly; its bounded observation SerialLane and scheduled work share that
     * physical thread. Logical responsibility therefore does not imply another
     * Java thread.</p>
     */
    synchronized TimingNodeExecutionResources createTimingNodeExecutionResources() {
        TimingNodeDataLane dataLane =
                new TimingNodeDataLane(
                        TIMING_NODE_DATA_LANE_QUEUE_CAPACITY,
                        TIMING_NODE_DATA_LANE_MAX_ITEMS_PER_RUN,
                        "TimingNodeData",
                        timingNodeExecutor);
        SerialTaskRunner taskRunner =
                new SerialTaskRunner(
                        TIMING_NODE_TASK_LANE_QUEUE_CAPACITY,
                        TIMING_NODE_TASK_LANE_MAX_ITEMS_PER_RUN,
                        "TimingNodeTask",
                        timingNodeExecutor);

        serialLanes.add(dataLane);
        serialLanes.add(taskRunner);
        return new TimingNodeExecutionResources(
                dataLane,
                taskRunner,
                tagProcessorScheduledExecutor);
    }

    /**
     * Creates a logical coordination lane for one Domain TimingSystemConductor.
     *
     * <p>The lane is logically owned by that TimingSystemConductor. Runtime owns the physical
     * coordination executor underneath it.</p>
     */
    synchronized SerialTaskRunner createTimingSystemConductorLane() {
        SerialTaskRunner conductorLane =
                new SerialTaskRunner(
                        TIMING_SYSTEM_CONDUCTOR_LANE_QUEUE_CAPACITY,
                        TIMING_SYSTEM_CONDUCTOR_LANE_MAX_ITEMS_PER_RUN,
                        "TimingSystemConductor",
                        timingSystemConductorExecutor);
        serialLanes.add(conductorLane);
        return conductorLane;
    }

    /** Creates the cooperative serial control lane owned by one AntennaManager. */
    synchronized SerialTaskRunner createAntennaControlLane(Duration operationTimeout) {
        SerialTaskRunner antennaControlLane =
                new SerialTaskRunner(
                        ANTENNA_CONTROL_LANE_QUEUE_CAPACITY,
                        ANTENNA_CONTROL_LANE_MAX_ITEMS_PER_RUN,
                        "AntennaManager",
                        sharedIoScheduledExecutor,
                        operationTimeout);
        serialLanes.add(antennaControlLane);
        return antennaControlLane;
    }

    ScheduledExecutorService sharedIoScheduledExecutor() {

        return sharedIoScheduledExecutor;
    }

    @Override
    public synchronized void close() {

        if (closed) {
            return;
        }
        closed = true;

        /*
         * Components normally close their lanes first. Closing again here is
         * intentional and idempotent: this is the bootstrap/failure fallback
         * for a partially built or partially started graph.
         */
        for (int index = serialLanes.size() - 1; index >= 0; index--) {
            try {
                serialLanes.get(index).close();
            } catch (Exception ignored) {

                // Preserve application/component shutdown failures instead.
            }
        }

        tagProcessorScheduledExecutor.shutdownNow();
        timingNodeExecutor.shutdownNow();
        timingSystemConductorExecutor.shutdownNow();
        sharedIoScheduledExecutor.shutdownNow();

        awaitTermination(tagProcessorScheduledExecutor);
        awaitTermination(timingNodeExecutor);
        awaitTermination(timingSystemConductorExecutor);
        awaitTermination(sharedIoScheduledExecutor);
    }

    /**
     * Creates one named normal-priority Runtime thread.
     *
     * <p>Keeping thread construction here makes the constructor describe the
     * execution topology instead of repeating JVM thread boilerplate.</p>
     */
    private static ThreadFactory threadFactory(String threadName) {

        return runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        };
    }

    private static void awaitTermination(ExecutorService executor) {

        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(100L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {

                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
