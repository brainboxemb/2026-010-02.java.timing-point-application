package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final int SHARED_IO_WORKERS = 2;
    private static final int SHARED_IO_QUEUE_CAPACITY = 16;

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

    private final ThreadPoolExecutor sharedIoExecutor;
    private final ScheduledThreadPoolExecutor antennaScheduler;

    private boolean started;
    private boolean closed;

    RuntimeExecutors() {
        timingNodeWorker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "tp-dml-node-worker");
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        tagProcessorWorker = new ScheduledThreadPoolExecutor(
                1,
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "tp-dml-tagproc-worker");
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                });
        tagProcessorWorker.setRemoveOnCancelPolicy(true);

        AtomicInteger ioWorkerNumber = new AtomicInteger();
        sharedIoExecutor = new ThreadPoolExecutor(
                SHARED_IO_WORKERS,
                SHARED_IO_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(
                        SHARED_IO_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "tp-io-shared-"
                                    + ioWorkerNumber.incrementAndGet());
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());

        antennaScheduler = new ScheduledThreadPoolExecutor(
                1,
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "tp-io-antenna-scheduler");
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                });
        antennaScheduler.setRemoveOnCancelPolicy(true);
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
        sharedIoExecutor.prestartAllCoreThreads();
        antennaScheduler.prestartAllCoreThreads();
        started = true;
    }

    synchronized boolean started() {
        return started;
    }

    /**
     * Creates node-local serial lanes on the two shared Domain role workers.
     *
     * <p>The NodeId is a logical lane identity for diagnostics only. It is no
     * longer part of a physical worker-thread name because that worker services
     * all configured nodes of the same role.</p>
     */
    synchronized TimingNodeExecutors createTimingNodeExecutors(
            NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException(
                    "nodeId must not be null");
        }

        SerialExecutor timingNode = new SerialExecutor(
                TIMING_NODE_QUEUE_CAPACITY,
                "TimingNode-" + nodeId.value(),
                timingNodeWorker);

        SerialScheduledExecutor tagProcessor =
                new SerialScheduledExecutor(
                        TAG_PROCESSOR_LANE_QUEUE_CAPACITY,
                        "TagProcessor-" + nodeId.value(),
                        tagProcessorWorker);

        serialLanes.add(timingNode);
        serialLanes.add(tagProcessor);
        return new TimingNodeExecutors(
                timingNode,
                tagProcessor);
    }

    /**
     * Creates the AntennaManager logical control lane on the shared blocking-I/O pool.
     */
    synchronized SerialExecutor createAntennaControlExecutor() {
        SerialExecutor antennaControl =
                new SerialExecutor(
                        ANTENNA_CONTROL_QUEUE_CAPACITY,
                        "AntennaManager",
                        sharedIoExecutor);
        serialLanes.add(antennaControl);
        return antennaControl;
    }

    ExecutorService sharedIoExecutor() {
        return sharedIoExecutor;
    }

    ScheduledExecutorService antennaScheduler() {
        return antennaScheduler;
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
        antennaScheduler.shutdownNow();
        sharedIoExecutor.shutdownNow();

        awaitTermination(tagProcessorWorker);
        awaitTermination(timingNodeWorker);
        awaitTermination(antennaScheduler);
        awaitTermination(sharedIoExecutor);
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
