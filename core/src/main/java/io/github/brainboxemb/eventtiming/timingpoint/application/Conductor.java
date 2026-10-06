package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates application-wide behaviour between already constructed components.
 *
 * <p>Runtime composition owns object construction and event wiring. Conductor owns
 * the behaviour of those relationships and executes that behaviour on its own
 * logical serial application lane.</p>
 *
 * <p>TimingNode status events are treated as change signals, not commands. The
 * callback only requests a reconcile. The reconcile itself reads the current
 * authoritative TimingNode state on the Conductor lane and derives the desired
 * antenna inventory state from that value.</p>
 *
 * <p>The lane is not a dedicated Java thread. Runtime owns the physical worker;
 * Conductor owns only its ordering boundary and activation lifecycle.</p>
 */
public final class Conductor {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;
    private final SerialExecutor serialExecutor;

    /*
     * Guarded by this. "reconcilePending" means one reconcile is queued or
     * running. Changes received while it is pending set "reconcileDirty" so one
     * later reconcile reads the newest authoritative state instead of queueing
     * every historical status snapshot.
     */
    private boolean reconcilePending;
    private boolean reconcileDirty;

    /*
     * Written only on the Conductor lane. This is desired application state,
     * not a claim about actual antenna hardware state.
     */
    private Boolean lastInventoryRequired;

    public Conductor(
            TimingNode timingNode,
            AntennaManager antennaManager,
            SerialExecutor serialExecutor) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        if (antennaManager == null) {
            throw new IllegalArgumentException(
                    "antennaManager must not be null");
        }
        if (serialExecutor == null) {
            throw new IllegalArgumentException(
                    "serialExecutor must not be null");
        }

        this.timingNode = timingNode;
        this.antennaManager = antennaManager;
        this.serialExecutor = serialExecutor;
    }

    /**
     * Activates application coordination and schedules the initial reconcile.
     *
     * <p>The initial reconcile reads current TimingNode state when it executes.
     * Startup therefore uses the same behaviour path as later status changes.</p>
     */
    public void activate() {
        serialExecutor.start();

        LOG.info(
                "Conductor activated for TimingNode {}",
                timingNode.timingNodeId().value());

        if (!requestReconcile(
                "activation")) {
            serialExecutor.close();
            throw new IllegalStateException(
                    "Conductor could not schedule initial status reconciliation");
        }
    }

    /**
     * Stops accepting application coordination and drains accepted work.
     */
    public void deactivate() {
        LOG.info(
                "Conductor deactivating for TimingNode {}",
                timingNode.timingNodeId().value());
        serialExecutor.close();
    }

    /**
     * Receives a synchronous TimingNode status-change notification.
     *
     * <p>The immutable event value proves that a change occurred, but it is not
     * queued as a command. The callback only requests one bounded reconcile and
     * returns. A downstream Conductor admission problem is diagnosed locally and
     * is never thrown back through the TimingNode event producer.</p>
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

        requestReconcile(
                "TimingNode status change");
    }

    /**
     * Requests one current-state reconcile.
     *
     * <p>If one is already queued/running, the request is coalesced by marking
     * it dirty. The running task will schedule exactly one follow-up reconcile
     * after it finishes. This avoids filling the bounded lane with transient
     * status snapshots while still preserving a change that arrives during
     * reconciliation.</p>
     *
     * @return {@code true} when the request is either accepted or safely
     *         coalesced; {@code false} when the lane rejected the request
     */
    private boolean requestReconcile(
            String source) {
        boolean coalesced = false;

        synchronized (this) {
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
     * Runs one reconcile and schedules one follow-up when a change arrived while
     * this task was pending/running.
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
    }

    /**
     * Reads authoritative current state and derives antenna inventory intent.
     */
    private void reconcileCurrentStatus() {
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
}
