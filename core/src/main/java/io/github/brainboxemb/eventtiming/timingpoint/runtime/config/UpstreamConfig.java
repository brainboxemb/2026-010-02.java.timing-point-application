package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Effective startup selection for the external upstream transport. */
public final class UpstreamConfig {
    public enum Transport {
        ENGINEERING_API
    }

    private final Transport transport;

    public UpstreamConfig(
            Transport transport) {
        failIfInvalidArgument(transport != null, "upstream transport must not be null");
        this.transport = transport;
    }

    public Transport transport() {
        return transport;
    }
}
