package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import java.util.Map;

/** Maps the application-level upstream transport selection. */
final class YamlUpstreamMapper {
    private static final String UPSTREAM = "upstream";
    private static final String TRANSPORT = "transport";
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
                TRANSPORT);
        if (!values.containsKey(TRANSPORT)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: upstream.transport");
        }

        String transport =
                YamlValues.requireString(
                                values.get(TRANSPORT),
                                "upstream.transport")
                        .trim();
        if (ENGINEERING_API.equals(transport)) {
            return new UpstreamConfig(
                    UpstreamConfig.Transport.ENGINEERING_API);
        }

        throw new IllegalArgumentException(
                "Unsupported upstream transport: " + transport);
    }

}
