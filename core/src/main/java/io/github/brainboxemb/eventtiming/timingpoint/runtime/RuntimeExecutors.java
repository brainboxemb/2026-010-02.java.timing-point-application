package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
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
    static final int TIMING_NODE_DATA_QUEUE_CAPACITY = 32;
    static final int TIMING_NODE_TASK_QUEUE_CAPACITY = 32;
    static final int TAG_PROCESSOR_LANE_QUEUE_CAPACITY = 32;
    static final int ANTENNA_CONTROL_QUEUE_CAPACITY = 8;
    static final int SIMULATION_QUEUE_CAPACITY = 32;
    static final int CONDUCTOR_QUEUE_CAPACITY = 8;

    /*
     * Step-5 baseline: one physical blocking-I/O thread.
     *
     * Additional physical I/O parallelism is a measurement-driven decision.
     * V01 must demonstrate a real bottleneck before this count is increased.
     */
    private static final int SHARED_IO_THREADS = 1;

    static final class TimingNodeLanes {
        private final SerialLane dataLane;
        private final SerialLane taskLane;
        private final SerialLane tagProcessorLane;
        private final ScheduledExecutorService tagProcessorScheduledExecutor;

        private TimingNodeLanes(
                SerialLane dataLane,
                SerialLane taskLane,
                SerialLane tagProcessorLane,
                ScheduledExecutorService tagProcessorScheduledExecutor) {
            this.dataLane = dataLane;
            this.taskLane = taskLane;
            this.tagProcessorLane = tagProcessorLane;
            this.tagProcessorScheduledExecutor = tagProcessorScheduledExecutor;
        }

        SerialLane dataLane() {
            return dataLane;
        }

        SerialLane taskLane() {
            return taskLane;
        }

        SerialLane tagProcessorLane() {
            return tagProcessorLane;
        }

        ScheduledExecutorService tagProcessorScheduledExecutor() {
            return tagProcessorScheduledExecutor;
        }
    }

    private final List<AutoCloseable> serialLanes =
            new ArrayList<AutoCloseable>();

    /*
     * Each serial lane schedules at most one drain token at a time, so these
     * backing queues are bounded structurally by the number of configured
     * lanes rather than by registration volume. Registration/command overload
     * remains bounded and visible in the lane-local queues.
     */
    private final ThreadPoolExecutor timingNodeExecutor;
    private final ScheduledThreadPoolExecutor tagProcessorScheduledExecutor;

    /**
     * Shared executor for TimingSystem coordination lanes.
     *
     * <p>TimingSystem coordination must not run synchronously on the emitting
     * Domain or I/O component thread. Logical system-Conductor lanes therefore use
     * this separate executor.</p>
     */
    private final ThreadPoolExecutor coordinationExecutor;

    /**
     * Shared scheduled I/O executor. AntennaManager
     * needs delayed multiplex rotation, while normal provider calls use the same
     * executor for immediate tasks.
     */
    private final ScheduledThreadPoolExecutor sharedIoScheduledExecutor;

    private boolean started;
    private boolean closed;

    RuntimeExecutors() {
        timingNodeExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory("tp-dl-node"),
                new ThreadPoolExecutor.AbortPolicy());
        tagProcessorScheduledExecutor = new ScheduledThreadPoolExecutor(
                1,
                threadFactory("tp-dl-tagproc"));
        tagProcessorScheduledExecutor.setRemoveOnCancelPolicy(true);

        coordinationExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory("tp-dl-system"),
                new ThreadPoolExecutor.AbortPolicy());

        sharedIoScheduledExecutor = new ScheduledThreadPoolExecutor(
                SHARED_IO_THREADS,
                threadFactory("tp-io-shared"));
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
        coordinationExecutor.prestartAllCoreThreads();
        sharedIoScheduledExecutor.prestartAllCoreThreads();
        started = true;
    }

    /**
     * Creates one node-local set of logical lanes on shared Runtime executors.
     *
     * <p>The Data Lane and Task Lane are distinct SerialLane instances with
     * independent bounded queues and FIFO ordering, but they deliberately share
     * the same TimingNode executor. TagProcessor keeps its separate scheduled
     * executor. Logical responsibility therefore does not imply another
     * Java thread.</p>
     */
    synchronized TimingNodeLanes createTimingNodeLanes() {
        SerialLane dataLane = new SerialLane(
                TIMING_NODE_DATA_QUEUE_CAPACITY,
                "TimingNodeData",
                timingNodeExecutor);
        SerialLane taskLane = new SerialLane(
                TIMING_NODE_TASK_QUEUE_CAPACITY,
                "TimingNodeTask",
                timingNodeExecutor);

        SerialLane tagProcessorLane = new SerialLane(
                TAG_PROCESSOR_LANE_QUEUE_CAPACITY, "TagProcessor", tagProcessorScheduledExecutor);

        serialLanes.add(dataLane);
        serialLanes.add(taskLane);
        serialLanes.add(tagProcessorLane);
        return new TimingNodeLanes(dataLane, taskLane, tagProcessorLane, tagProcessorScheduledExecutor);
    }

    /**
     * Creates a logical coordination lane for one Domain system Conductor.
     *
     * <p>The lane is logically owned by that system Conductor. Runtime owns the physical
     * coordination executor underneath it.</p>
     */
    synchronized SerialLane createSystemConductorLane() {
        SerialLane conductorLane = new SerialLane(
                CONDUCTOR_QUEUE_CAPACITY, "SystemConductor", coordinationExecutor);
        serialLanes.add(conductorLane);
        return conductorLane;
    }

    /**
     * Creates one ordinary serial AntennaManager control lane.
     *
     * <p>Delayed work is scheduled separately and later admitted to this same
     * lane, so timing does not create a second kind of lane.</p>
     */
    synchronized SerialLane createAntennaControlLane() {
        SerialLane antennaControlLane = new SerialLane(
                ANTENNA_CONTROL_QUEUE_CAPACITY, "AntennaManager", sharedIoScheduledExecutor);
        serialLanes.add(antennaControlLane);
        return antennaControlLane;
    }

    ScheduledExecutorService sharedIoScheduledExecutor() {
        return sharedIoScheduledExecutor;
    }

    /**
     * Creates the engineering simulated-tag lane on the shared scheduled I/O
     * executor. Scenario delays therefore release its thread while still
     * keeping a bounded logical execution lane.
     */
    synchronized SerialLane createSimulationLane() {
        SerialLane simulationLane = new SerialLane(
                SIMULATION_QUEUE_CAPACITY, "SimulatedTagScenario", sharedIoScheduledExecutor);
        serialLanes.add(simulationLane);
        return simulationLane;
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
        for (int index = serialLanes.size() - 1;
                index >= 0;
                index--) {
            try {
                serialLanes.get(index).close();
            } catch (Exception ignored) {
                // Preserve application/component shutdown failures instead.
            }
        }

        tagProcessorScheduledExecutor.shutdownNow();
        timingNodeExecutor.shutdownNow();
        coordinationExecutor.shutdownNow();
        sharedIoScheduledExecutor.shutdownNow();

        awaitTermination(tagProcessorScheduledExecutor);
        awaitTermination(timingNodeExecutor);
        awaitTermination(coordinationExecutor);
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
