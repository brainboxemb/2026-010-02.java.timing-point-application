package oss.relay.eventtiming.testclient.central;

import oss.relay.eventtiming.upstreamdata.UpstreamData;

/** Only outbound capability visible to the Central simulator boundary. */
@FunctionalInterface
public interface CentralTransport {
    void send(UpstreamData data);
}
