package io.github.brainboxemb.eventtiming.testclient.central;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/** Only outbound capability visible to the Central simulator boundary. */
@FunctionalInterface
public interface CentralTransport {
    void send(UpstreamData data);
}
