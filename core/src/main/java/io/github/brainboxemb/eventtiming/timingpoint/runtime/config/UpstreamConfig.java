package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import java.time.Duration;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Effective startup selection for the external upstream transport. */
public final class UpstreamConfig {
    public enum Transport {
        ENGINEERING_API
    }

    public static final Duration DEFAULT_TIMING_DATA_REPLAY_PACKET_DELAY =
            Duration.ZERO;

    private final Transport transport;
    private final Duration timingDataReplayPacketDelay;

    public UpstreamConfig(
            Transport transport) {
        this(
                transport,
                DEFAULT_TIMING_DATA_REPLAY_PACKET_DELAY);
    }

    public UpstreamConfig(
            Transport transport,
            Duration timingDataReplayPacketDelay) {
        failIfInvalidArgument(
                transport != null,
                "upstream transport must not be null");
        failIfInvalidArgument(
                timingDataReplayPacketDelay != null
                        && !timingDataReplayPacketDelay.isNegative(),
                "TIMING_DATA replay packet delay must not be negative");
        this.transport = transport;
        this.timingDataReplayPacketDelay = timingDataReplayPacketDelay;
    }

    public Transport transport() {
        return transport;
    }

    public Duration timingDataReplayPacketDelay() {
        return timingDataReplayPacketDelay;
    }
}
