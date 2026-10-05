package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;

/**
 * Coordinates application-wide behaviour between already constructed components.
 *
 * <p>Runtime composition owns object construction and event wiring. Conductor owns
 * the behaviour of those relationships and executes that behaviour on its own
 * logical serial application lane.</p>
 *
 * <p>The lane is not a dedicated Java thread. Runtime owns the physical worker;
 * Conductor owns only its ordering boundary and activation lifecycle.</p>
 */
public final class Conductor {

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;
    private final SerialExecutor serialExecutor;

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
     * Activates application coordination.
     *
     * <p>The initial reconcile is itself queued on the Conductor lane. It reads
     * the current TimingNode status when it actually executes, so an event that
     * races with activation cannot be overwritten by a stale startup snapshot.</p>
     */
    public void activate() {
        serialExecutor.start();

        SerialExecutor.AdmissionResult admission =
                serialExecutor.offer(
                        this::reconcileCurrentStatus);
        if (admission != SerialExecutor.AdmissionResult.ACCEPTED) {
            serialExecutor.close();
            throw new IllegalStateException(
                    "Conductor could not schedule initial status reconciliation: "
                            + admission);
        }
    }

    /**
     * Stops accepting application coordination and drains already accepted work.
     *
     * <p>Activation order places Conductor after the coordinated components, so
     * reverse deactivation drains this lane while those components are still
     * available.</p>
     */
    public void deactivate() {
        serialExecutor.close();
    }

    /**
     * Receives a TimingNode status notification.
     *
     * <p>Event delivery is synchronous on the producer thread, so this callback
     * deliberately performs no cross-component behaviour. It only hands the
     * immutable status value to the Conductor lane and returns.</p>
     *
     * <p>Notifications received before activation or after deactivation may be
     * ignored: activation always reconciles the current TimingNode status.</p>
     */
    public void onTimingNodeStatusChanged(
            Status status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "status must not be null");
        }

        if (serialExecutor.state()
                != SerialExecutor.State.RUNNING) {
            return;
        }

        SerialExecutor.AdmissionResult admission =
                serialExecutor.offer(
                        () -> applyTimingNodeStatus(status));
        if (admission == SerialExecutor.AdmissionResult.FULL) {
            throw new IllegalStateException(
                    "Conductor queue is full while applying TimingNode status");
        }
    }

    /**
     * Reads the latest state on the Conductor lane instead of capturing a
     * possibly stale status before the queued reconcile runs.
     */
    private void reconcileCurrentStatus() {
        applyTimingNodeStatus(
                timingNode.query(
                        TimingNodeQueries.status()));
    }

    /**
     * Application rule: an OPEN TimingNode permits antenna inventory.
     */
    private void applyTimingNodeStatus(
            Status status) {
        antennaManager.requestInventoryEnabled(
                status.lifecycle() == Lifecycle.OPEN);
    }
}
