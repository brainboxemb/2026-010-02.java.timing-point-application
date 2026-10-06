package io.github.brainboxemb.eventtiming.timingpoint.application;

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
 * Tracks one named application-level value from an authoritative source.
 *
 * <p>Source events do not supply this property's value. They only call
 * {@link #signalChanged()}, after which the property schedules a refresh on the
 * supplied Application serial lane and reads the authoritative current value
 * through the configured reader.</p>
 *
 * <p>Repeated change signals are coalesced while a refresh is queued/running.
 * Registered change handlers run on the same Application lane and only when the
 * effective value actually changed.</p>
 *
 * <p>This class is deliberately small. It is not a reactive graph, rule engine
 * or worker owner. The supplied SerialExecutor remains owned by Conductor/
 * Runtime composition.</p>
 *
 * @param <T> tracked immutable/value-semantic application value
 */
public final class ApplicationProperty<T> {
    private static final Logger LOG =
            LoggerFactory.getLogger(ApplicationProperty.class);

    private final String name;
    private final SerialExecutor serialExecutor;
    private final Supplier<T> reader;
    private final List<Consumer<T>> changeHandlers =
            new ArrayList<Consumer<T>>();

    /*
     * Guarded by this.
     *
     * Before initialize(), source signals only mark the property dirty. The
     * explicit initial refresh then reads authoritative state once components
     * are ready.
     */
    private boolean initializationStarted;
    private boolean initialized;
    private T currentValue;
    private boolean refreshPending;
    private boolean refreshDirty;

    public ApplicationProperty(
            String name,
            SerialExecutor serialExecutor,
            Supplier<T> reader) {
        if (name == null
                || name.trim().isEmpty()) {
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
     * Adds application behaviour for a real effective-value change.
     *
     * <p>Handlers are configured before initialization so the property has one
     * stable behaviour set for its runtime lifetime.</p>
     */
    public synchronized void onChange(
            Consumer<T> handler) {
        if (handler == null) {
            throw new IllegalArgumentException(
                    "handler must not be null");
        }
        if (initializationStarted) {
            throw new IllegalStateException(
                    "change handlers must be registered before property initialization");
        }

        changeHandlers.add(
                handler);
    }

    /**
     * Performs the first authoritative read and waits for its application
     * behaviour to complete.
     *
     * <p>Signals received before/during startup are consumed by this initial
     * refresh. If another signal arrives while the initial value is being
     * processed, the same lane task rereads until it observes a clean point
     * before activation continues.</p>
     */
    public void initialize() {
        synchronized (this) {
            if (initializationStarted) {
                throw new IllegalStateException(
                        "ApplicationProperty "
                                + name
                                + " is already initialized/initializing");
            }

            initializationStarted = true;
            refreshPending = true;

            /*
             * Any signal before startup is already covered by the authoritative
             * read that is about to run.
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
     * <p>Before initialization this only marks pending work; the initial read
     * covers it. During normal operation this method performs bounded admission
     * to the existing Application lane and returns immediately.</p>
     *
     * @return true when the signal was safely coalesced/admitted, false when a
     *         running property could not admit its refresh
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

        logRejectedSignal(
                admission);
        return false;
    }

    /**
     * Convenience listener for wiring any source event directly to this
     * property's change signal; the event payload is intentionally ignored.
     */
    public <S> Consumer<S> changeSignal() {
        return ignored -> signalChanged();
    }

    public synchronized boolean initialized() {
        return initialized;
    }

    /**
     * Returns the last authoritative value observed by this property.
     */
    public synchronized T currentValue() {
        if (!initialized) {
            throw new IllegalStateException(
                    "ApplicationProperty "
                            + name
                            + " has no initialized value");
        }
        return currentValue;
    }

    /**
     * Initial startup refresh. It stays in one lane task until no source signal
     * arrived during the previous read/handler execution.
     */
    private void runInitialRefreshUntilClean() {
        try {
            boolean rerun;
            do {
                synchronized (this) {
                    refreshDirty = false;
                }

                refreshValue();

                synchronized (this) {
                    rerun = refreshDirty;
                }
            } while (rerun);
        } finally {
            synchronized (this) {
                refreshPending = false;
            }
        }
    }

    /**
     * Normal asynchronous refresh. One signal received during the refresh is
     * preserved as one later refresh so this task yields the shared lane.
     */
    private void runRefresh() {
        synchronized (this) {
            refreshDirty = false;
        }

        try {
            refreshValue();
        } catch (RuntimeException ex) {
            LOG.warn(
                    "ApplicationProperty {} refresh failed",
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

    /**
     * Reads and applies one authoritative value.
     */
    private void refreshValue() {
        T nextValue =
                reader.get();
        if (nextValue == null) {
            throw new IllegalStateException(
                    "ApplicationProperty "
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
                        "ApplicationProperty {} remains {}",
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
                    "ApplicationProperty {} initialized to {}",
                    name,
                    nextValue);
        } else {
            LOG.info(
                    "ApplicationProperty {} changed {} -> {}",
                    name,
                    previousValue,
                    nextValue);
        }

        for (Consumer<T> handler : handlers) {
            handler.accept(
                    nextValue);
        }
    }

    private void awaitInitialization(
            Future<Void> future) {
        try {
            future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while initializing ApplicationProperty "
                            + name,
                    ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException(
                    "Initialization cancelled for ApplicationProperty "
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
                    "Initialization failed for ApplicationProperty "
                            + name,
                    cause);
        }
    }

    private IllegalStateException admissionFailure(
            String operation,
            SerialExecutor.AdmissionResult admission) {
        return new IllegalStateException(
                "ApplicationProperty "
                        + name
                        + " "
                        + operation
                        + " could not enter Application lane: "
                        + admission,
                serialExecutor.failure());
    }

    private void logRejectedSignal(
            SerialExecutor.AdmissionResult admission) {
        if (admission
                == SerialExecutor.AdmissionResult.FULL) {
            LOG.warn(
                    "ApplicationProperty {} change signal could not be admitted because the Application lane is full",
                    name);
            return;
        }

        if (serialExecutor.state()
                == SerialExecutor.State.FAILED) {
            LOG.error(
                    "ApplicationProperty {} cannot refresh because the Application lane failed",
                    name,
                    serialExecutor.failure());
        } else {
            LOG.debug(
                    "ApplicationProperty {} ignored change signal because Application lane state is {}",
                    name,
                    serialExecutor.state());
        }
    }
}
