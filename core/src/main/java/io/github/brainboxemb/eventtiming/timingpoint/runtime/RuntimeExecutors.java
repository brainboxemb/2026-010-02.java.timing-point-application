package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
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
    static final int TIMING_NODE_QUEUE_CAPACITY = 32;
    static final int TAG_PROCESSOR_LANE_QUEUE_CAPACITY = 32;
    static final int ANTENNA_CONTROL_QUEUE_CAPACITY = 8;
    static final int CONDUCTOR_QUEUE_CAPACITY = 8;

    /*
     * Step-5 baseline: one physical blocking-I/O worker.
     *
     * Additional physical I/O parallelism is a measurement-driven decision.
     * V01 must demonstrate a real bottleneck before this count is increased.
     */
    private static final int SHARED_IO_WORKERS = 1;

    static final class TimingNodeExecutors {
        private final SerialExecutor timingNode;
        private final SerialScheduledExecutor tagProcessor;

        private TimingNodeExecutors(
                SerialExecutor timingNode,
                SerialScheduledExecutor tagProcessor) {
            this.timingNode = timingNode;
            this.tagProcessor = tagProcessor;
        }

        SerialExecutor timingNode() {
            return timingNode;
        }

        SerialScheduledExecutor tagProcessor() {
            return tagProcessor;
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
     * Shared worker for application-level coordination lanes.
     *
     * <p>Application coordination must not run synchronously on the emitting
     * Domain or I/O component thread. Logical application lanes therefore use
     * this separate worker.</p>
     */
    private final ThreadPoolExecutor applicationWorker;

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
                threadFactory("tp-dml-node-worker"),
                new ThreadPoolExecutor.AbortPolicy());
        tagProcessorWorker = new ScheduledThreadPoolExecutor(
                1,
                threadFactory("tp-dml-tagproc-worker"));
        tagProcessorWorker.setRemoveOnCancelPolicy(true);

        applicationWorker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                threadFactory("tp-apl-worker"),
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
        applicationWorker.prestartAllCoreThreads();
        sharedIoWorker.prestartAllCoreThreads();
        started = true;
    }

    synchronized boolean started() {
        return started;
    }

    /**
     * Creates one node-local pair of logical serial lanes on the two shared
     * Domain role workers.
     *
     * <p>Runtime execution knows the functional roles, not Domain identities.
     * Every invocation returns distinct lane objects, while all returned lanes
     * use the same role workers.</p>
     */
    synchronized TimingNodeExecutors createTimingNodeExecutors() {
        SerialExecutor timingNode = new SerialExecutor(
                TIMING_NODE_QUEUE_CAPACITY,
                "TimingNode",
                timingNodeWorker);

        SerialScheduledExecutor tagProcessor =
                new SerialScheduledExecutor(
                        TAG_PROCESSOR_LANE_QUEUE_CAPACITY,
                        "TagProcessor",
                        tagProcessorWorker);

        serialLanes.add(timingNode);
        serialLanes.add(tagProcessor);
        return new TimingNodeExecutors(
                timingNode,
                tagProcessor);
    }

    /**
     * Creates the Conductor's serial application-coordination lane.
     *
     * <p>The lane is logically owned by Conductor. Runtime owns the physical
     * application worker underneath it.</p>
     */
    synchronized SerialExecutor createConductorExecutor() {
        SerialExecutor conductor =
                new SerialExecutor(
                        CONDUCTOR_QUEUE_CAPACITY,
                        "Conductor",
                        applicationWorker);
        serialLanes.add(conductor);
        return conductor;
    }

    /**
     * Creates one AntennaManager control lane on the shared scheduled I/O worker.
     *
     * <p>The manager sees only the project SerialScheduledExecutor abstraction.
     * The JDK ScheduledExecutorService remains a Runtime implementation detail.</p>
     */
    synchronized SerialScheduledExecutor createAntennaControlExecutor() {
        SerialScheduledExecutor antennaControl =
                new SerialScheduledExecutor(
                        ANTENNA_CONTROL_QUEUE_CAPACITY,
                        "AntennaManager",
                        sharedIoWorker);
        serialLanes.add(antennaControl);
        return antennaControl;
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
        applicationWorker.shutdownNow();
        sharedIoWorker.shutdownNow();

        awaitTermination(tagProcessorWorker);
        awaitTermination(timingNodeWorker);
        awaitTermination(applicationWorker);
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
