package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;

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
     * Applies the current TimingNode lifecycle to antenna operation.
     *
     * <p>The composition root subscribes this method to the relevant
     * TimingNode status event. Calling it once after component startup also
     * synchronizes the initial state.</p>
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

        antennaManager.requestOperational(
                status.lifecycle() == Lifecycle.OPEN);
    }
}
