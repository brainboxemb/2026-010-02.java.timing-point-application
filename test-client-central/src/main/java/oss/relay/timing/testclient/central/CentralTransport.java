package oss.relay.timing.testclient.central;

import oss.relay.timing.upstreamdata.UpstreamData;

/** Only outbound capability visible to the Central simulator boundary. */
@FunctionalInterface
public interface CentralTransport {
    void send(UpstreamData data);
}
