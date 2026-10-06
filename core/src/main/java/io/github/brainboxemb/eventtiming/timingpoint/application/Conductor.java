package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates application-wide behaviour between already constructed components.
 *
 * <p>Runtime composition owns object construction, event wiring and physical
 * execution resources. Conductor owns the application-level lifecycle and
 * coordination of the components it receives.</p>
 *
 * <p>{@link ComponentLifecycleManager} performs only ordered activation,
 * rollback and reverse deactivation. Conductor decides when that lifecycle runs
 * and performs application actions after the components are ready.</p>
 *
 * <p>TimingNode status events are treated as change signals, not commands.
 * During startup they are only marked as pending. After component activation,
 * Conductor reads the current authoritative TimingNode state on its own serial
 * lane and derives the desired antenna inventory state from that value.</p>
 */
public final class Conductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;
    private final SerialExecutor serialExecutor;
    private final ComponentLifecycleManager componentLifecycle =
            new ComponentLifecycleManager();

    /*
     * Guarded by this. During startup no reconcile work is admitted: component
     * activation first reaches a stable point, then one authoritative current
     * state is reconciled. Afterwards reconcilePending/reconcileDirty coalesce
     * bursts without losing a change that arrives while reconciliation runs.
     */
    private boolean startupInProgress;
    private boolean reconcilePending;
    private boolean reconcileDirty;

    /*
     * Written only on the Conductor lane. This is desired application state,
     * not a claim about actual antenna hardware state.
     */
    private Boolean lastInventoryRequired;

    /**
     * Creates one application coordinator.
     *
     * @param timingNode required TimingNode component
     * @param antennaManager optional antenna component; {@code null} when the
     *        composition contains no antennas
     * @param serialExecutor Conductor logical serial lane on the Runtime-owned
     *        application worker
     */
    public Conductor(
            TimingNode timingNode,
            AntennaManager antennaManager,
            SerialExecutor serialExecutor) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        if (serialExecutor == null) {
            throw new IllegalArgumentException(
                    "serialExecutor must not be null");
        }

        this.timingNode = timingNode;
        this.antennaManager = antennaManager;
        this.serialExecutor = serialExecutor;

        componentLifecycle.register(
                "TimingNode " + timingNode.timingNodeId().value(),
                timingNode::activate,
                timingNode::deactivate);

        if (antennaManager != null) {
            componentLifecycle.register(
                    "AntennaManager",
                    antennaManager::activate,
                    antennaManager::deactivate);
        }
    }

    /**
     * Activates the application components in order and establishes current
     * application intent before returning.
     *
     * <p>Status notifications raised while components activate are not executed
     * immediately. The initial reconcile reads authoritative current state after
     * all component activations have completed. This prevents a startup event
     * from controlling a component that is not ready yet.</p>
     */
    public void activate() {
        synchronized (this) {
            startupInProgress = true;
            reconcileDirty = false;
        }

        try {
            componentLifecycle.activateAll();
            serialExecutor.start();

            synchronized (this) {
                /*
                 * The initial authoritative reconcile consumes all status
                 * changes seen during activation. Mark it pending before
                 * releasing the startup gate so a concurrent new change cannot
                 * queue ahead of it.
                 */
                startupInProgress = false;
                reconcilePending = true;
                reconcileDirty = false;
            }

            runInitialReconcile();

            LOG.info(
                    "Conductor activated application components for TimingNode {}",
                    timingNode.timingNodeId().value());
        } catch (RuntimeException ex) {
            cleanupAfterActivationFailure(
                    ex);
            throw ex;
        } catch (Error error) {
            cleanupAfterActivationFailure(
                    error);
            throw error;
        }
    }

    /**
     * Stops Conductor coordination, then deactivates application components in
     * reverse activation order.
     *
     * <p>The serial lane is drained before component shutdown so already
     * accepted coordination cannot run against components that are disappearing.</p>
     */
    public void deactivate() {
        LOG.info(
                "Conductor deactivating application components for TimingNode {}",
                timingNode.timingNodeId().value());

        Throwable firstFailure = null;

        try {
            serialExecutor.close();
        } catch (RuntimeException ex) {
            firstFailure = ex;
        } catch (Error error) {
            firstFailure = error;
        }

        try {
            componentLifecycle.deactivateAll();
        } catch (RuntimeException ex) {
            firstFailure =
                    appendFailure(
                            firstFailure,
                            ex);
        } catch (Error error) {
            firstFailure =
                    appendFailure(
                            firstFailure,
                            error);
        }

        rethrow(
                firstFailure);
    }

    /**
     * Receives a synchronous TimingNode status-change notification.
     *
     * <p>The event value proves that a change occurred, but it is not queued as
     * a command. During startup the change is only marked dirty. During normal
     * operation the callback requests one bounded reconcile and returns.</p>
     */
    public void onTimingNodeStatusChanged(
            Status status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "status must not be null");
        }

        LOG.debug(
                "TimingNode {} status change signalled to Conductor: lifecycle={}",
                status.timingNodeId().value(),
                status.lifecycle());

        synchronized (this) {
            if (startupInProgress) {
                reconcileDirty = true;
                LOG.debug(
                        "Deferred Conductor reconcile for TimingNode {} until component startup completes",
                        timingNode.timingNodeId().value());
                return;
            }
        }

        requestReconcile(
                "TimingNode status change");
    }

    /**
     * Requests one current-state reconcile.
     *
     * <p>If one is already queued/running, the request is coalesced by marking
     * it dirty. The running task schedules one follow-up reconcile after it
     * finishes.</p>
     */
    private boolean requestReconcile(
            String source) {
        boolean coalesced = false;

        synchronized (this) {
            if (startupInProgress) {
                reconcileDirty = true;
                return true;
            }

            if (reconcilePending) {
                reconcileDirty = true;
                coalesced = true;
            } else {
                reconcilePending = true;
            }
        }

        if (coalesced) {
            LOG.debug(
                    "Coalesced Conductor reconcile request for TimingNode {} from {}",
                    timingNode.timingNodeId().value(),
                    source);
            return true;
        }

        SerialExecutor.AdmissionResult admission =
                serialExecutor.offer(
                        this::runReconcile);

        if (admission == SerialExecutor.AdmissionResult.ACCEPTED) {
            return true;
        }

        synchronized (this) {
            reconcilePending = false;
            reconcileDirty = true;
        }

        switch (admission) {
            case FULL:
                LOG.warn(
                        "Conductor reconcile admission FULL for TimingNode {} from {}; current state was not reconciled",
                        timingNode.timingNodeId().value(),
                        source);
                return false;
            case NOT_RUNNING:
                if (serialExecutor.state()
                        == SerialExecutor.State.FAILED) {
                    LOG.error(
                            "Conductor reconcile could not run for TimingNode {} because its lane failed",
                            timingNode.timingNodeId().value(),
                            serialExecutor.failure());
                } else {
                    LOG.debug(
                            "Conductor reconcile ignored for TimingNode {} from {} because lane state is {}",
                            timingNode.timingNodeId().value(),
                            source,
                            serialExecutor.state());
                }
                return false;
            default:
                throw new IllegalStateException(
                        "Unsupported Conductor admission result "
                                + admission);
        }
    }

    /**
     * Runs the first reconcile as result-bearing lane work so Conductor
     * activation does not complete before the application intent is established.
     */
    private void runInitialReconcile() {
        SerialExecutor.SubmitResult<Void> submission =
                serialExecutor.submit(
                        () -> {
                            try {
                                reconcileCurrentStatus();
                            } finally {
                                finishReconcile();
                            }
                            return null;
                        });

        switch (submission.admission()) {
            case ACCEPTED:
                awaitInitialReconcile(
                        submission.futureResult());
                return;
            case FULL:
                synchronized (this) {
                    reconcilePending = false;
                    reconcileDirty = true;
                }
                throw new IllegalStateException(
                        "Conductor queue is full during initial reconciliation");
            case NOT_RUNNING:
                synchronized (this) {
                    reconcilePending = false;
                    reconcileDirty = true;
                }
                throw new IllegalStateException(
                        "Conductor lane is not running during initial reconciliation",
                        serialExecutor.failure());
            default:
                throw new IllegalStateException(
                        "Unsupported Conductor admission result "
                                + submission.admission());
        }
    }

    /**
     * Runs a normal fire-and-forget reconcile on the Conductor lane.
     */
    private void runReconcile() {
        synchronized (this) {
            reconcileDirty = false;
        }

        try {
            reconcileCurrentStatus();
        } catch (RuntimeException ex) {
            LOG.warn(
                    "Conductor reconciliation failed for TimingNode {}",
                    timingNode.timingNodeId().value(),
                    ex);
        } finally {
            finishReconcile();
        }
    }

    /**
     * Completes one reconcile and preserves a change received while it ran.
     */
    private void finishReconcile() {
        boolean rerun;

        synchronized (this) {
            rerun = reconcileDirty;
            reconcilePending = false;
        }

        if (rerun) {
            requestReconcile(
                    "coalesced status change");
        }
    }

    /**
     * Reads authoritative current state and derives antenna inventory intent.
     */
    private void reconcileCurrentStatus() {
        if (antennaManager == null) {
            return;
        }

        Status status =
                timingNode.query(
                        TimingNodeQueries.status());
        boolean inventoryRequired =
                status.lifecycle() == Lifecycle.OPEN;

        if (lastInventoryRequired == null
                || lastInventoryRequired.booleanValue()
                        != inventoryRequired) {
            LOG.info(
                    "TimingNode {} lifecycle {} sets antenna inventory required={}",
                    status.timingNodeId().value(),
                    status.lifecycle(),
                    inventoryRequired);
            lastInventoryRequired =
                    Boolean.valueOf(
                            inventoryRequired);
        } else {
            LOG.debug(
                    "TimingNode {} reconcile keeps antenna inventory required={} for lifecycle {}",
                    status.timingNodeId().value(),
                    inventoryRequired,
                    status.lifecycle());
        }

        boolean accepted =
                antennaManager.requestInventoryEnabled(
                        inventoryRequired);

        if (!accepted) {
            LOG.warn(
                    "AntennaManager rejected inventory reconciliation for TimingNode {} lifecycle {} required={} managerState={}",
                    status.timingNodeId().value(),
                    status.lifecycle(),
                    inventoryRequired,
                    antennaManager.state());
        }
    }

    private void awaitInitialReconcile(
            Future<Void> future) {
        try {
            future.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Interrupted while waiting for initial Conductor reconciliation",
                    ex);
        } catch (CancellationException ex) {
            throw new IllegalStateException(
                    "Initial Conductor reconciliation was cancelled",
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
                    "Initial Conductor reconciliation failed",
                    cause);
        }
    }

    private void cleanupAfterActivationFailure(
            Throwable originalFailure) {
        synchronized (this) {
            startupInProgress = false;
            reconcilePending = false;
        }

        try {
            serialExecutor.close();
        } catch (RuntimeException ex) {
            originalFailure.addSuppressed(
                    ex);
        } catch (Error error) {
            originalFailure.addSuppressed(
                    error);
        }

        try {
            componentLifecycle.deactivateAll();
        } catch (RuntimeException ex) {
            originalFailure.addSuppressed(
                    ex);
        } catch (Error error) {
            originalFailure.addSuppressed(
                    error);
        }
    }

    private static Throwable appendFailure(
            Throwable firstFailure,
            Throwable laterFailure) {
        if (firstFailure == null) {
            return laterFailure;
        }
        firstFailure.addSuppressed(
                laterFailure);
        return firstFailure;
    }

    private static void rethrow(
            Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        throw (Error) failure;
    }
}
