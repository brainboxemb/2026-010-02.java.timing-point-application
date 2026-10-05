package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Internal runtime ownership for one configured antenna-processing path.
 *
 * <p>This is composition/lifecycle glue, not an architecture-level device or
 * domain component. It makes the application start/stop order explicit while
 * AntennaManager, TagProcessor and the shared I/O executor retain their own
 * execution responsibilities.</p>
 */
final class AntennaRuntime implements AutoCloseable {
    private final AntennaManager antennaManager;
    private final TagProcessor tagProcessor;
    private final ExecutorService sharedIoExecutor;
    private final Consumer<TagObservation> observationListener;

    private boolean started;
    private boolean closed;

    AntennaRuntime(
            AntennaManager antennaManager,
            TagProcessor tagProcessor,
            ExecutorService sharedIoExecutor) {
        if (antennaManager == null) {
            throw new IllegalArgumentException("antennaManager must not be null");
        }
        if (tagProcessor == null) {
            throw new IllegalArgumentException("tagProcessor must not be null");
        }
        if (sharedIoExecutor == null) {
            throw new IllegalArgumentException("sharedIoExecutor must not be null");
        }
        this.antennaManager = antennaManager;
        this.tagProcessor = tagProcessor;
        this.sharedIoExecutor = sharedIoExecutor;
        this.observationListener = tagProcessor::onObservation;
    }

    synchronized void start() {
        if (started || closed) {
            throw new IllegalStateException(
                    "AntennaRuntime can only start once");
        }

        tagProcessor.start();
        boolean subscribed = false;
        try {
            subscribeAll();
            subscribed = true;
            antennaManager.start();
            started = true;
        } catch (RuntimeException ex) {
            try {
                antennaManager.close();
            } catch (RuntimeException ignored) {
                // Preserve the startup failure.
            }
            if (subscribed) {
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

    private void subscribeAll() {
        for (Antenna antenna : antennaManager.antennas()) {
            if (!antenna.observations().subscribe(observationListener)) {
                throw new IllegalStateException(
                        "TagProcessor observation listener was already subscribed");
            }
        }
    }

    private void unsubscribeAll() {
        for (Antenna antenna : antennaManager.antennas()) {
            antenna.observations().unsubscribe(observationListener);
        }
    }

    private void shutdownSharedIo() {
        sharedIoExecutor.shutdown();
        boolean interrupted = false;
        try {
            if (!sharedIoExecutor.awaitTermination(2L, TimeUnit.SECONDS)) {
                sharedIoExecutor.shutdownNow();
                sharedIoExecutor.awaitTermination(2L, TimeUnit.SECONDS);
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
