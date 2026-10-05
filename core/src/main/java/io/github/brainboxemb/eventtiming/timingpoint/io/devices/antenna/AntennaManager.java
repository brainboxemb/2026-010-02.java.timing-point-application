package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle/control owner for the configured antennas of one TimingSystem.
 *
 * <p>Blocking provider calls never run on antenna callback, TagProcessor or
 * TimingNode lanes. Control work is admitted to a bounded per-manager queue and
 * drained serially on a shared bounded I/O ExecutorService supplied by runtime
 * composition. The manager therefore preserves per-manager ordering without
 * owning a dedicated Java thread.</p>
 */
public final class AntennaManager implements AutoCloseable {

    public enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED
    }

    public enum FailureReason {
        OVERLOADED,
        TIMEOUT,
        INTERRUPTED,
        PROVIDER_FAILURE
    }

    /** Visible failure of one result-bearing antenna lifecycle operation. */
    public static final class ControlException extends RuntimeException {
        private final FailureReason reason;

        private ControlException(
                FailureReason reason,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public FailureReason reason() {
            return reason;
        }
    }

    private interface ControlAction {
        void run();
    }

    private final List<Antenna> antennas;
    private final ExecutorService sharedIoExecutor;
    private final ArrayBlockingQueue<FutureTask<Void>> controlQueue;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final long controlTimeoutNanos;

    private State state = State.NEW;
    private Throwable failure;

    public AntennaManager(
            List<Antenna> antennas,
            ExecutorService sharedIoExecutor,
            int controlQueueCapacity,
            Duration controlTimeout) {
        if (antennas == null || antennas.isEmpty()) {
            throw new IllegalArgumentException("antennas must contain at least one antenna");
        }
        if (sharedIoExecutor == null) {
            throw new IllegalArgumentException("sharedIoExecutor must not be null");
        }
        if (controlQueueCapacity < 1) {
            throw new IllegalArgumentException("controlQueueCapacity must be positive");
        }
        if (controlTimeout == null || controlTimeout.isZero() || controlTimeout.isNegative()) {
            throw new IllegalArgumentException("controlTimeout must be positive");
        }

        List<Antenna> copy = new ArrayList<>(antennas.size());
        for (Antenna antenna : antennas) {
            if (antenna == null) {
                throw new IllegalArgumentException("antennas must not contain null");
            }
            copy.add(antenna);
        }

        this.antennas = Collections.unmodifiableList(copy);
        this.sharedIoExecutor = sharedIoExecutor;
        this.controlQueue = new ArrayBlockingQueue<>(controlQueueCapacity);
        try {
            this.controlTimeoutNanos = controlTimeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("controlTimeout is too large", ex);
        }
    }

    /**
     * Probes, initializes and starts inventory on every configured antenna.
     *
     * <p>The caller waits only for this explicit lifecycle transition; decoded
     * observation delivery remains asynchronous through each Antenna EventSource.</p>
     */
    public void start() {
        synchronized (this) {
            if (state != State.NEW) {
                throw new IllegalStateException(
                        "AntennaManager can only start from NEW; current state=" + state);
            }
            state = State.STARTING;
        }

        try {
            runControl(this::startAll);
            synchronized (this) {
                state = State.RUNNING;
            }
        } catch (RuntimeException ex) {
            synchronized (this) {
                failure = ex;
                state = State.FAILED;
            }
            throw ex;
        }
    }

    public synchronized State state() {
        return state;
    }

    public synchronized Throwable failure() {
        return failure;
    }

    public List<Antenna> antennas() {
        return antennas;
    }

    @Override
    public void close() {
        State current;
        synchronized (this) {
            current = state;
            if (current == State.STOPPED) {
                return;
            }
            if (current == State.STARTING || current == State.STOPPING) {
                throw new IllegalStateException(
                        "AntennaManager cannot close during transition; current state=" + current);
            }
            state = State.STOPPING;
        }

        RuntimeException closeFailure = null;
        try {
            runControl(this::stopAndCloseAll);
        } catch (RuntimeException ex) {
            closeFailure = ex;
        }

        synchronized (this) {
            if (closeFailure == null) {
                state = State.STOPPED;
            } else {
                failure = closeFailure;
                state = State.FAILED;
            }
        }

        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private void startAll() {
        try {
            for (Antenna antenna : antennas) {
                antenna.probe();
            }
            for (Antenna antenna : antennas) {
                antenna.initialize();
            }
            for (Antenna antenna : antennas) {
                antenna.startInventory();
            }
        } catch (RuntimeException | Error ex) {
            rollbackStartedAntennas();
            throw ex;
        }
    }

    private void rollbackStartedAntennas() {
        for (int index = antennas.size() - 1; index >= 0; index--) {
            Antenna antenna = antennas.get(index);
            try {
                if (antenna.inventoryRunning()) {
                    antenna.stopInventory();
                }
            } catch (RuntimeException ignored) {
                // Preserve the startup failure; best-effort rollback continues.
            }
            try {
                antenna.close();
            } catch (RuntimeException ignored) {
                // Preserve the startup failure; best-effort rollback continues.
            }
        }
    }

    private void stopAndCloseAll() {
        RuntimeException firstFailure = null;

        for (int index = antennas.size() - 1; index >= 0; index--) {
            Antenna antenna = antennas.get(index);
            try {
                if (antenna.inventoryRunning()) {
                    antenna.stopInventory();
                }
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
        }

        for (int index = antennas.size() - 1; index >= 0; index--) {
            try {
                antennas.get(index).close();
            } catch (RuntimeException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                }
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void runControl(ControlAction action) {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });

        if (!controlQueue.offer(task)) {
            throw controlFailure(
                    FailureReason.OVERLOADED,
                    "AntennaManager control queue is full",
                    null);
        }

        try {
            scheduleDrain();
        } catch (RuntimeException ex) {
            controlQueue.remove(task);
            task.cancel(false);
            throw ex;
        }

        try {
            task.get(controlTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            task.cancel(true);
            throw controlFailure(
                    FailureReason.TIMEOUT,
                    "AntennaManager control operation timed out",
                    ex);
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw controlFailure(
                    FailureReason.INTERRUPTED,
                    "AntennaManager control operation was interrupted",
                    ex);
        } catch (CancellationException ex) {
            throw controlFailure(
                    FailureReason.OVERLOADED,
                    "AntennaManager control operation was cancelled before execution",
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            throw controlFailure(
                    FailureReason.PROVIDER_FAILURE,
                    "AntennaManager provider operation failed",
                    cause == null ? ex : cause);
        }
    }

    private void scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            sharedIoExecutor.execute(this::drainControlQueue);
        } catch (RejectedExecutionException ex) {
            drainScheduled.set(false);
            cancelPendingControl();
            throw controlFailure(
                    FailureReason.OVERLOADED,
                    "Shared antenna I/O executor rejected control work",
                    ex);
        }
    }

    private void drainControlQueue() {
        try {
            FutureTask<Void> task;
            while ((task = controlQueue.poll()) != null) {
                task.run();
            }
        } finally {
            drainScheduled.set(false);
            if (!controlQueue.isEmpty()) {
                try {
                    scheduleDrain();
                } catch (ControlException ignored) {
                    // scheduleDrain already cancels pending work on shared-I/O rejection.
                }
            }
        }
    }

    private void cancelPendingControl() {
        FutureTask<Void> task;
        while ((task = controlQueue.poll()) != null) {
            task.cancel(false);
        }
    }

    private static ControlException controlFailure(
            FailureReason reason,
            String message,
            Throwable cause) {
        return new ControlException(reason, message, cause);
    }
}
