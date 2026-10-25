package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import java.time.Duration;
import java.util.Map;

/** Maps the application-level upstream transport selection. */
final class YamlUpstreamMapper {
    private static final String UPSTREAM = "upstream";
    private static final String TRANSPORT = "transport";
    private static final String TIMING_DATA_REPLAY_PACKET_DELAY_MILLIS =
            "timingDataReplayPacketDelayMillis";
    private static final String ENGINEERING_API = "engineering-api";

    private YamlUpstreamMapper() {
    }

    /**
     * Maps upstream startup selection.
     *
     * <p>An absent section preserves the current reference-application behavior
     * and therefore selects the Engineering API connector. An explicitly named
     * but unimplemented transport is rejected instead of silently falling back.</p>
     */
    static UpstreamConfig map(
            Object rawUpstream) {
        if (rawUpstream == null) {
            return new UpstreamConfig(
                    UpstreamConfig.Transport.ENGINEERING_API);
        }

        Map<?, ?> values =
                YamlValues.requireMapping(
                        rawUpstream,
                        UPSTREAM);
        YamlValues.rejectUnknownFields(
                values,
                UPSTREAM,
                TRANSPORT,
                TIMING_DATA_REPLAY_PACKET_DELAY_MILLIS);
        if (!values.containsKey(TRANSPORT)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: upstream.transport");
        }

        String transport =
                YamlValues.requireString(
                                values.get(TRANSPORT),
                                "upstream.transport")
                        .trim();
        if (!ENGINEERING_API.equals(transport)) {
            throw new IllegalArgumentException(
                    "Unsupported upstream transport: " + transport);
        }

        long replayDelayMillis = 0L;
        if (values.containsKey(
                TIMING_DATA_REPLAY_PACKET_DELAY_MILLIS)) {
            replayDelayMillis =
                    YamlValues.requireLong(
                            values.get(
                                    TIMING_DATA_REPLAY_PACKET_DELAY_MILLIS),
                            "upstream."
                                    + TIMING_DATA_REPLAY_PACKET_DELAY_MILLIS);
            if (replayDelayMillis < 0L) {
                throw new IllegalArgumentException(
                        "upstream."
                                + TIMING_DATA_REPLAY_PACKET_DELAY_MILLIS
                                + " must not be negative");
            }
        }

        return new UpstreamConfig(
                UpstreamConfig.Transport.ENGINEERING_API,
                Duration.ofMillis(
                        replayDelayMillis));
    }

}
