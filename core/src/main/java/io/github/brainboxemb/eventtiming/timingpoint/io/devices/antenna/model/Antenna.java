package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model;

import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInfo;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;

import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Decoded antenna boundary used by SI-01.
 *
 * <p>Concrete implementations keep vendor protocol and device-session details
 * behind this interface. Observations are published as decoded immutable values.
 * TimingNode state and TimingData persistence are not antenna responsibilities.</p>
 *
 * <p>Construction is passive: creating/wiring an Antenna must not start
 * inventory or background device activity. Hardware interaction belongs to
 * {@link #selfTest()}, {@link #initialize()} and the explicit inventory lifecycle.
 * This keeps Runtime composition free of hidden activation side effects.</p>
 */
public interface Antenna {

    /** Performs the startup self-test without starting inventory. */
    AntennaInfo selfTest();

    /** Initializes the antenna for normal inventory operation. */
    void initialize();

    /** Starts decoded tag-observation delivery. */
    void startInventory();

    /** Stops decoded tag-observation delivery. */
    void stopInventory();

    /** Returns whether normal inventory delivery is active. */
    boolean inventoryRunning();

    /** Returns the subscription-only event emitted when this antenna observes a tag. */
    EventSource<TagObservation> tagObservedEvent();

    /**
     * Stops delivery and releases provider/device resources.
     *
     * <p>Shutdown is valid even when normal initialization did not complete.</p>
     */
    void shutdown();
}
