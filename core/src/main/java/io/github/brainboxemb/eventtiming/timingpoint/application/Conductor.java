package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

/**
 * Coordinates application-wide behaviour between already constructed components.
 *
 * <p>Runtime composition owns object construction and event wiring. Conductor only
 * owns the application rule that relates those components; it does not discover
 * components, subscribe events or own worker threads.</p>
 */
public final class Conductor {

    private final TimingNode timingNode;
    private final AntennaManager antennaManager;

    public Conductor(
            TimingNode timingNode,
            AntennaManager antennaManager) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        if (antennaManager == null) {
            throw new IllegalArgumentException(
                    "antennaManager must not be null");
        }

        this.timingNode = timingNode;
        this.antennaManager = antennaManager;
    }

    /**
     * Activates application-wide coordination after the coordinated components
     * themselves are active.
     *
     * <p>The event subscription handles later changes. Activation reconciles the
     * current TimingNode status once, using exactly the same behaviour as a later
     * status-changed event. Startup/recovery semantics remain owned by TimingNode;
     * Conductor only reacts to the status TimingNode exposes.</p>
     */
    public void activate() {
        applyTimingNodeStatus(
                timingNode.query(
                        TimingNodeQueries.status()));
    }

    /**
     * Conductor owns no worker or external resource.
     */
    public void deactivate() {
        // Nothing to release.
    }

    /**
     * Applies the current TimingNode lifecycle to antenna inventory permission.
     *
     * <p>The composition root subscribes this method to the relevant
     * TimingNode status event. Startup/recovery lifecycle semantics are owned
     * by the TimingNode OPEN/CLOSE TimingData design track; Conductor only
     * applies status changes it receives.</p>
     */
    public void onTimingNodeStatusChanged(
            Status status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "status must not be null");
        }
        applyTimingNodeStatus(status);
    }

    /**
     * Applies the application rule shared by initial reconciliation and later
     * status-change events.
     */
    private void applyTimingNodeStatus(
            Status status) {
        antennaManager.requestInventoryEnabled(
                status.lifecycle() == Lifecycle.OPEN);
    }
}
