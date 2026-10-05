package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central construction and fallback lifecycle owner for runtime execution resources.
 *
 * <p>The runtime decides which execution lanes exist, how they are bounded and how
 * their threads are named. Domain and I/O components receive those executors as
 * dependencies; they do not invent their own production threads.</p>
 *
 * <p>Dedicated component lanes are still started/stopped by the component whose
 * lifecycle they execute. This class tracks them so a partially built application
 * can always be cleaned up. Shared blocking-I/O and antenna scheduling pools remain
 * runtime-owned for their full lifetime.</p>
 *
 * <p>All project-owned threads intentionally use the JVM default priority. Runtime
 * correctness must not depend on Java thread priority; role-specific priority tuning
 * remains measurement-driven.</p>
 */
final class RuntimeExecutors implements AutoCloseable {
    static final int TIMING_NODE_QUEUE_CAPACITY = 32;

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

    private final List<AutoCloseable> dedicatedExecutors =
            new ArrayList<AutoCloseable>();
    private final ThreadPoolExecutor sharedIoExecutor;
    private final ScheduledThreadPoolExecutor antennaScheduler;

    RuntimeExecutors() {
        AtomicInteger ioWorkerNumber = new AtomicInteger();
        sharedIoExecutor = new ThreadPoolExecutor(
                SHARED_IO_WORKERS,
                SHARED_IO_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(SHARED_IO_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "tp-io-shared-" + ioWorkerNumber.incrementAndGet());
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
     * Creates the two dedicated serial lanes owned by one TimingNode aggregate.
     *
     * <p>The TimingNode lane serializes node state/commit work. The TagProcessor
     * lane serializes decoded-observation processing and scheduled housekeeping.
     * They are separate so bursty RFID processing cannot execute on the mutable
     * TimingNode state lane.</p>
     */
    synchronized TimingNodeExecutors createTimingNodeExecutors(NodeId nodeId) {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId must not be null");
        }

        SerialExecutor timingNode = new SerialExecutor(
                TIMING_NODE_QUEUE_CAPACITY,
                "tp-dml-node-" + nodeId.value());
        SerialScheduledExecutor tagProcessor =
                new SerialScheduledExecutor(
                        "tp-dml-tag-" + nodeId.value());

        dedicatedExecutors.add(timingNode);
        dedicatedExecutors.add(tagProcessor);
        return new TimingNodeExecutors(timingNode, tagProcessor);
    }

    ExecutorService sharedIoExecutor() {
        return sharedIoExecutor;
    }

    ScheduledExecutorService antennaScheduler() {
        return antennaScheduler;
    }

    @Override
    public void close() {
        /*
         * Components normally close their dedicated lanes first. Closing them
         * again here is deliberate and safe: this is the bootstrap/failure
         * fallback for partially constructed or partially started graphs.
         */
        for (int index = dedicatedExecutors.size() - 1; index >= 0; index--) {
            try {
                dedicatedExecutors.get(index).close();
            } catch (Exception ignored) {
                // Preserve application/component shutdown failures instead.
            }
        }

        antennaScheduler.shutdownNow();
        sharedIoExecutor.shutdownNow();
        awaitTermination(antennaScheduler);
        awaitTermination(sharedIoExecutor);
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
