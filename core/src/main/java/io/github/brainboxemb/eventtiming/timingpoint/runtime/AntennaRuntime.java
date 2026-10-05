package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Internal runtime ownership for one configured antenna-processing path.
 *
 * <p>TimingNode status changes only submit antenna lifecycle work; provider I/O
 * remains on AntennaManager's shared-I/O control lane.</p>
 */
final class AntennaRuntime implements AutoCloseable {
    private final AntennaManager antennaManager;
    private final TagProcessor tagProcessor;
    private final TimingNode timingNode;
    private final ExecutorService sharedIoExecutor;
    private final Consumer<TagObservation> observationListener;
    private final Consumer<Status> timingNodeStatusListener;

    private boolean started;
    private boolean closed;

    AntennaRuntime(
            AntennaManager antennaManager,
            TagProcessor tagProcessor,
            TimingNode timingNode,
            ExecutorService sharedIoExecutor) {
        if (antennaManager == null) {
            throw new IllegalArgumentException(
                    "antennaManager must not be null");
        }
        if (tagProcessor == null) {
            throw new IllegalArgumentException(
                    "tagProcessor must not be null");
        }
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        if (sharedIoExecutor == null) {
            throw new IllegalArgumentException(
                    "sharedIoExecutor must not be null");
        }
        this.antennaManager = antennaManager;
        this.tagProcessor = tagProcessor;
        this.timingNode = timingNode;
        this.sharedIoExecutor = sharedIoExecutor;
        this.observationListener = tagProcessor::onObservation;
        this.timingNodeStatusListener = this::onTimingNodeStatus;
    }

    synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException(
                    "AntennaRuntime can only start once");
        }

        tagProcessor.start();
        boolean observationsSubscribed = false;
        boolean statusSubscribed = false;
        try {
            subscribeAll();
            observationsSubscribed = true;

            if (!timingNode.statusChangedEvent()
                    .subscribe(timingNodeStatusListener)) {
                throw new IllegalStateException(
                        "AntennaRuntime TimingNode status listener was already subscribed");
            }
            statusSubscribed = true;

            /*
             * Startup checks antenna health only. Normal inventory follows the
             * TimingNode OPEN/CLOSED lifecycle through the non-blocking listener.
             */
            antennaManager.start();

            Status current =
                    timingNode.query(TimingNodeQueries.status());
            antennaManager.requestOperational(
                    current.lifecycle() == Lifecycle.OPEN);
            started = true;
        } catch (RuntimeException ex) {
            if (statusSubscribed) {
                timingNode.statusChangedEvent()
                        .unsubscribe(timingNodeStatusListener);
            }
            try {
                antennaManager.close();
            } catch (RuntimeException ignored) {
                // Preserve the startup failure.
            }
            if (observationsSubscribed) {
                unsubscribeAll();
            }
            tagProcessor.stop();
            shutdownSharedIo();
            closed = true;
            throw ex;
        }
    }

    synchronized boolean started() {
        return started;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        RuntimeException firstFailure = null;

        timingNode.statusChangedEvent()
                .unsubscribe(timingNodeStatusListener);

        /*
         * Stop observation producers before removing listeners. TagProcessor is
         * kept alive until after unsubscribe so every observation already
         * delivered into its bounded queue can still drain.
         */
        try {
            antennaManager.close();
        } catch (RuntimeException ex) {
            firstFailure = ex;
        }

        unsubscribeAll();

        try {
            tagProcessor.stop();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }

        shutdownSharedIo();
        started = false;
        closed = true;

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void onTimingNodeStatus(Status status) {
        antennaManager.requestOperational(
                status.lifecycle() == Lifecycle.OPEN);
    }

    private void subscribeAll() {
        for (Antenna antenna : antennaManager.antennas()) {
            if (!antenna.observations()
                    .subscribe(observationListener)) {
                throw new IllegalStateException(
                        "TagProcessor observation listener was already subscribed");
            }
        }
    }

    private void unsubscribeAll() {
        for (Antenna antenna : antennaManager.antennas()) {
            antenna.observations()
                    .unsubscribe(observationListener);
        }
    }

    private void shutdownSharedIo() {
        sharedIoExecutor.shutdown();
        boolean interrupted = false;
        try {
            if (!sharedIoExecutor.awaitTermination(
                    2L,
                    TimeUnit.SECONDS)) {
                sharedIoExecutor.shutdownNow();
                sharedIoExecutor.awaitTermination(
                        2L,
                        TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            interrupted = true;
            sharedIoExecutor.shutdownNow();
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
