package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Decoded antenna boundary used by SI-01.
 *
 * <p>Concrete implementations keep vendor protocol and device-session details
 * behind this interface. Observations are published as decoded immutable values.
 * TimingNode state and TimingData persistence are not antenna responsibilities.</p>
 */
public interface Antenna extends AutoCloseable {

    /** Performs a one-shot identity/version probe without starting inventory. */
    AntennaInfo probe();

    /** Initializes the antenna for normal inventory operation. */
    void initialize();

    /** Starts decoded tag-observation delivery. */
    void startInventory();

    /** Stops decoded tag-observation delivery. */
    void stopInventory();

    /** Returns whether normal inventory delivery is active. */
    boolean inventoryRunning();

    /** Returns the subscription-only decoded observation event. */
    EventSource<TagObservation> observations();

    /** Stops delivery and releases antenna resources. */
    @Override
    void close();
}
