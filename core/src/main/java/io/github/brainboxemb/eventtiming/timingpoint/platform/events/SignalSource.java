package io.github.brainboxemb.eventtiming.timingpoint.platform.events;

/** Subscription-only view of a local payload-less signal. */
public interface SignalSource {

    /**
     * Adds one listener during application composition.
     *
     * @return true when the listener was newly added
     */
    boolean subscribe(Runnable listener);
}
