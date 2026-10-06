package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks one named value from an authoritative reader on a supplied serial lane.
 *
 * <p>A source signal never supplies the tracked value directly. It only says
 * that the authoritative source may have changed. This class coalesces repeated
 * signals, schedules a refresh on the supplied lane, reads the current value and
 * notifies handlers only when the effective value changed.</p>
 *
 * <p>This is reusable infrastructure only. It has no knowledge of application
 * components or domain types and it owns no worker thread.</p>
 *
 * @param <T> immutable/value-semantic tracked value
 */
public final class TrackedProperty<T> {
    private static final Logger LOG =
            LoggerFactory.getLogger(TrackedProperty.class);

    private final String name;
    private final SerialExecutor serialExecutor;
    private final Supplier<T> reader;
    private final List<Consumer<T>> changeHandlers =
            new ArrayList<Consumer<T>>();

    /*
     * Guarded by this. Before initialize(), signals only mark the property
     * dirty. The explicit initial read happens after the owning application
     * components are ready.
     */
    private boolean initializationStarted;
    private boolean initialized;
    private T currentValue;
    private boolean refreshPending;
    private boolean refreshDirty;

    public TrackedProperty(
            String name,
            SerialExecutor serialExecutor,
            Supplier<T> reader) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "name must not be blank");
        }
        if (serialExecutor == null) {
            throw new IllegalArgumentException(
                    "serialExecutor must not be null");
        }
        if (reader == null) {
            throw new IllegalArgumentException(
                    "reader must not be null");
        }

        this.name = name.trim();
        this.serialExecutor = serialExecutor;
        this.reader = reader;
    }

    public String name() {
        return name;
    }

    /**
     * Registers behaviour for a real tracked-value change.
     *
     * <p>Handlers are fixed before initialization so runtime behaviour does not
     * change while the property is active.</p>
     */
    public synchronized void onChange(
            Consumer<T> handler) {
        if (handler == null) {
            throw new IllegalArgumentException(
                    "handler must not be null");
        }
        if (initializationStarted) {
            throw new IllegalStateException(
                    "change handlers must be registered before initialization");
        }
        changeHandlers.add(handler);
    }

    /**
     * Performs the first authoritative read and waits until its handlers have
     * completed on the supplied serial lane.
     */
    public void initialize() {
        synchronized (this) {
            if (initializationStarted) {
                throw new IllegalStateException(
                        "TrackedProperty "
                                + name
                                + " is already initialized/initializing");
            }

            initializationStarted = true;
            refreshPending = true;

            /*
             * A signal received before initialization is already covered by
             * the authoritative read that is about to run.
             */
            refreshDirty = false;
        }

        SerialExecutor.SubmitResult<Void> submission =
                serialExecutor.submit(
                        () -> {
                            runInitialRefreshUntilClean();
                            return null;
                        });

        if (submission.admission()
                != SerialExecutor.AdmissionResult.ACCEPTED) {
            synchronized (this) {
                refreshPending = false;
                refreshDirty = true;
            }
            throw admissionFailure(
                    "initialization",
                    submission.admission());
        }

        awaitInitialization(
                submission.futureResult());
    }

    /**
     * Signals that the authoritative source may have changed.
     *
     * <p>Before initialization the signal is remembered for the initial read.
     * During normal operation this method only performs bounded admission and
     * returns; the authoritative read and handlers execute on the supplied
     * serial lane.</p>
     */
    public boolean signalChanged() {
        synchronized (this) {
            refreshDirty = true;

            if (!initializationStarted) {
                return true;
            }

            if (refreshPending) {
                return true;
            }

            refreshPending = true;
        }

        SerialExecutor.AdmissionResult admission =
                serialExecutor.offer(
                        this::runRefresh);

        if (admission
                == SerialExecutor.AdmissionResult.ACCEPTED) {
            return true;
        }

        synchronized (this) {
            refreshPending = false;
            refreshDirty = true;
        }

        logRejectedSignal(admission);
        return false;
    }

    public synchronized boolean initialized() {
        return initialized;
    }

    /** Returns the last authoritative value observed by this property. */
    public synchronized T currentValue() {
        if (!initialized) {
            throw new IllegalStateException(
                    "TrackedProperty "
                            + name
                            + " has no initialized value");
        }
        return currentValue;
    }

    /**
     * Keeps the initial refresh in one lane task until no source signal arrived
     * during the previous read/handler execution.
     */
    private void runInitialRefreshUntilClean() {
        try {
            while (true) {
                synchronized (this) {
                    refreshDirty = false;
                }

                refreshValue();

                synchronized (this) {
                    if (!refreshDirty) {
                        /*
                         * Dirty-check and transition to idle form one atomic
                         * decision. A signal after this point sees
                         * refreshPending=false and schedules normal refresh work.
                         */
                        refreshPending = false;
                        return;
                    }
                }
            }
        } catch (RuntimeException ex) {
            synchronized (this) {
                refreshPending = false;
            }
            throw ex;
        } catch (Error error) {
            synchronized (this) {
                refreshPending = false;
            }
            throw error;
        }
    }

    /**
     * Runs one normal asynchronous refresh and preserves one later refresh when
     * another signal arrives while this one is running.
     */
    private void runRefresh() {
        synchronized (this) {
            refreshDirty = false;
        }

        try {
            refreshValue();
        } catch (RuntimeException ex) {
            LOG.warn(
                    "TrackedProperty {} refresh failed",
                    name,
                    ex);
        } finally {
            boolean rerun;

            synchronized (this) {
                rerun = refreshDirty;
                refreshPending = false;
            }

            if (rerun) {
                signalChanged();
            }
        }
    }

    /** Reads one authoritative value and applies real changes. */
    private void refreshValue() {
        T nextValue = reader.get();
        if (nextValue == null) {
            throw new IllegalStateException(
                    "TrackedProperty "
                            + name
                            + " reader returned null");
        }

        final T previousValue;
        final List<Consumer<T>> handlers;

        synchronized (this) {
            if (initialized
                    && Objects.equals(
                            currentValue,
                            nextValue)) {
                LOG.debug(
                        "TrackedProperty {} remains {}",
                        name,
                        nextValue);
                return;
            }

            previousValue = currentValue;
            currentValue = nextValue;
            initialized = true;
            handlers =
                    new ArrayList<Consumer<T>>(
                            changeHandlers);
        }

        if (previousValue == null) {
            LOG.info(
                    "TrackedProperty {} initialized to {}",
                    name,
                    nextValue);
        } else {
            LOG.info(
                    "TrackedProperty {} changed {} -> {}",
                    name,
                    previousValue,
                    nextValue);
        }

        for (Consumer<T> handler : handlers) {
            handler.accept(nextValue);
        }
    }

    private void awaitInitialization(
            Future<Void> future) {
        try {
            future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while initializing TrackedProperty "
                            + name,
                    ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException(
                    "Initialization cancelled for TrackedProperty "
                            + name,
                    ex);
        } catch (ExecutionException ex) {
            Throwable cause =
                    ex.getCause() == null
                            ? ex
                            : ex.getCause();

            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(
                    "Initialization failed for TrackedProperty "
                            + name,
                    cause);
        }
    }

    private IllegalStateException admissionFailure(
            String operation,
            SerialExecutor.AdmissionResult admission) {
        return new IllegalStateException(
                "TrackedProperty "
                        + name
                        + " "
                        + operation
                        + " could not enter serial lane: "
                        + admission,
                serialExecutor.failure());
    }

    private void logRejectedSignal(
            SerialExecutor.AdmissionResult admission) {
        if (admission
                == SerialExecutor.AdmissionResult.FULL) {
            LOG.warn(
                    "TrackedProperty {} change signal could not be admitted because the serial lane is full",
                    name);
            return;
        }

        if (serialExecutor.state()
                == SerialExecutor.State.FAILED) {
            LOG.error(
                    "TrackedProperty {} cannot refresh because the serial lane failed",
                    name,
                    serialExecutor.failure());
        } else {
            LOG.debug(
                    "TrackedProperty {} ignored change signal because serial lane state is {}",
                    name,
                    serialExecutor.state());
        }
    }
}
