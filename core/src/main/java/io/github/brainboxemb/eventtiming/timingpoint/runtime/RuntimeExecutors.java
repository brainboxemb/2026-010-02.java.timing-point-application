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
 * <p>Logical serial lanes remain per TimingNode/TagProcessor, but physical
 * workers are shared by functional role. This keeps per-node FIFO/admission
 * state without multiplying Java threads as nodes are added on the Raspberry Pi
 * Zero baseline.</p>
 *
 * <p>Blocking I/O remains separate from the Domain processing workers. All
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
     * Step-5 baseline: one physical blocking-I/O worker.
     *
     * Additional physical I/O parallelism is a measurement-driven decision.
     * V01 must demonstrate a real bottleneck before this count is increased.
     */
    private static final int SHARED_IO_WORKERS = 1;

    static final class TimingNodeLanes {
        private final SerialLane dataLane;
        private final SerialLane taskLane;
        private final SerialLane tagProcessor;
        private final ScheduledExecutorService tagProcessorScheduler;

        private TimingNodeLanes(
                SerialLane dataLane,
                SerialLane taskLane,
                SerialLane tagProcessor,
                ScheduledExecutorService tagProcessorScheduler) {
            this.dataLane = dataLane;
            this.taskLane = taskLane;
            this.tagProcessor = tagProcessor;
            this.tagProcessorScheduler = tagProcessorScheduler;
        }

        SerialLane dataLane() {
            return dataLane;
        }

        SerialLane taskLane() {
            return taskLane;
        }

        SerialLane tagProcessor() {
            return tagProcessor;
        }

        ScheduledExecutorService tagProcessorScheduler() {
            return tagProcessorScheduler;
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
    private final ThreadPoolExecutor timingNodeWorker;
    private final ScheduledThreadPoolExecutor tagProcessorWorker;

    /**
     * Shared worker for TimingSystem coordination lanes.
     *
     * <p>TimingSystem coordination must not run synchronously on the emitting
     * Domain or I/O component thread. Logical system-Conductor lanes therefore use
     * this separate worker.</p>
     */
    private final ThreadPoolExecutor coordinationWorker;

    /**
     * Shared physical I/O worker. It is scheduled-capable because AntennaManager
     * needs delayed multiplex rotation, while normal provider calls use the same
     * worker as immediate tasks.
     */
    private final ScheduledThreadPoolExecutor sharedIoWorker;

    private boolean started;
    private boolean closed;

    RuntimeExecutors() {
        timingNodeWorker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory("tp-dl-node-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        tagProcessorWorker = new ScheduledThreadPoolExecutor(
                1,
                threadFactory("tp-dl-tagproc-worker"));
        tagProcessorWorker.setRemoveOnCancelPolicy(true);

        coordinationWorker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory("tp-dl-system-worker"),
                new ThreadPoolExecutor.AbortPolicy());

        sharedIoWorker = new ScheduledThreadPoolExecutor(
                SHARED_IO_WORKERS,
                threadFactory("tp-io-shared-worker"));
        sharedIoWorker.setRemoveOnCancelPolicy(true);
    }

    /**
     * Starts the physical workers owned by this Runtime.
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

        timingNodeWorker.prestartAllCoreThreads();
        tagProcessorWorker.prestartAllCoreThreads();
        coordinationWorker.prestartAllCoreThreads();
        sharedIoWorker.prestartAllCoreThreads();
        started = true;
    }

    synchronized boolean started() {
        return started;
    }

    /**
     * Creates one node-local set of logical lanes on shared Runtime workers.
     *
     * <p>The Data Lane and Task Lane are distinct SerialLane instances with
     * independent bounded queues and FIFO ordering, but they deliberately share
     * the same physical node worker. TagProcessor keeps its separately scheduled
     * role worker. Logical responsibility therefore does not imply another
     * Java thread.</p>
     */
    synchronized TimingNodeLanes createTimingNodeLanes() {
        SerialLane dataLane = new SerialLane(
                TIMING_NODE_DATA_QUEUE_CAPACITY,
                "TimingNodeData",
                timingNodeWorker);
        SerialLane taskLane = new SerialLane(
                TIMING_NODE_TASK_QUEUE_CAPACITY,
                "TimingNodeTask",
                timingNodeWorker);

        SerialLane tagProcessor =
                new SerialLane(
                        TAG_PROCESSOR_LANE_QUEUE_CAPACITY,
                        "TagProcessor",
                        tagProcessorWorker);

        serialLanes.add(dataLane);
        serialLanes.add(taskLane);
        serialLanes.add(tagProcessor);
        return new TimingNodeLanes(
                dataLane,
                taskLane,
                tagProcessor,
                tagProcessorWorker);
    }

    /**
     * Creates a logical coordination lane for one Domain system Conductor.
     *
     * <p>The lane is logically owned by that system Conductor. Runtime owns the physical
     * coordination worker underneath it.</p>
     */
    synchronized SerialLane createSystemConductorLane() {
        SerialLane conductor =
                new SerialLane(
                        CONDUCTOR_QUEUE_CAPACITY,
                        "SystemConductor",
                        coordinationWorker);
        serialLanes.add(conductor);
        return conductor;
    }

    /**
     * Creates one ordinary serial AntennaManager control lane.
     *
     * <p>Delayed work is scheduled separately and later admitted to this same
     * lane, so timing does not create a second kind of lane.</p>
     */
    synchronized SerialLane createAntennaControlLane() {
        SerialLane antennaControl =
                new SerialLane(
                        ANTENNA_CONTROL_QUEUE_CAPACITY,
                        "AntennaManager",
                        sharedIoWorker);
        serialLanes.add(antennaControl);
        return antennaControl;
    }

    ScheduledExecutorService sharedIoScheduler() {
        return sharedIoWorker;
    }

    /**
     * Creates the engineering simulated-tag lane on the shared scheduled I/O
     * worker. Scenario delays therefore release the physical worker while still
     * keeping a bounded logical execution lane.
     */
    synchronized SerialLane createSimulationLane() {
        SerialLane simulation =
                new SerialLane(
                        SIMULATION_QUEUE_CAPACITY,
                        "SimulatedTagScenario",
                        sharedIoWorker);
        serialLanes.add(simulation);
        return simulation;
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

        tagProcessorWorker.shutdownNow();
        timingNodeWorker.shutdownNow();
        coordinationWorker.shutdownNow();
        sharedIoWorker.shutdownNow();

        awaitTermination(tagProcessorWorker);
        awaitTermination(timingNodeWorker);
        awaitTermination(coordinationWorker);
        awaitTermination(sharedIoWorker);
    }

    /**
     * Creates one named normal-priority Runtime worker.
     *
     * <p>Keeping thread construction here makes the constructor describe the
     * execution topology instead of repeating JVM thread boilerplate.</p>
     */
    private static ThreadFactory threadFactory(
            String threadName) {
        return runnable -> {
            Thread thread =
                    new Thread(
                            runnable,
                            threadName);
            thread.setPriority(
                    Thread.NORM_PRIORITY);
            return thread;
        };
    }

    private static void awaitTermination(
            ExecutorService executor) {
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try {
                    executor.awaitTermination(
                            100L,
                            TimeUnit.MILLISECONDS);
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
