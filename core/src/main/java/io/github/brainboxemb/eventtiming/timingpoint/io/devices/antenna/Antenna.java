package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

/**
 * Stable decoded antenna-observation boundary used by SI-01.
 *
 * <p>Concrete antenna implementations own device/protocol mechanics. They
 * publish decoded observations through this callback contract and do not mutate
 * TimingNode state or persistence directly.</p>
 */
public interface Antenna extends AutoCloseable {

    /** Listener for decoded antenna observations. */
    @FunctionalInterface
    interface ObservationListener {
        void onObservation(Observation observation);
    }

    /** One decoded tag observation with its accepted observation time. */
    final class Observation {
        private final String tagId;
        private final TimingTimestamp time;

        public Observation(String tagId, TimingTimestamp time) {
            if (tagId == null || tagId.trim().isEmpty()) {
                throw new IllegalArgumentException("tagId must not be blank");
            }
            if (time == null) {
                throw new IllegalArgumentException("time must not be null");
            }
            this.tagId = tagId.trim();
            this.time = time;
        }

        public String tagId() {
            return tagId;
        }

        public TimingTimestamp time() {
            return time;
        }
    }

    /**
     * Starts observation delivery to one listener.
     *
     * <p>The callback is an ingress boundary. It must be able to return after
     * bounded TimingNode admission rather than waiting for persistence/commit.</p>
     */
    void start(ObservationListener listener);

    /** Returns whether this antenna is currently delivering observations. */
    boolean running();

    /** Stops observation delivery. */
    @Override
    void close();
}
