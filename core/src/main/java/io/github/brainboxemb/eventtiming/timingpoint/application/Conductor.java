package io.github.brainboxemb.eventtiming.timingpoint.application;

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

    private final AntennaManager antennaManager;

    public Conductor(
            AntennaManager antennaManager) {
        this.antennaManager = antennaManager;
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
        if (antennaManager == null) {
            return;
        }

        antennaManager.requestInventoryEnabled(
                status.lifecycle() == Lifecycle.OPEN);
    }
}
