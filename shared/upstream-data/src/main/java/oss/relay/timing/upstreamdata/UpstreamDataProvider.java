package oss.relay.timing.upstreamdata;

import oss.relay.timing.timingdata.TimingDataCodec;

/**
 * Typed provider SPI for one concrete UpstreamData representation.
 */
public interface UpstreamDataProvider {
    String id();

    UpstreamDataCodec createCodec(TimingDataCodec timingDataCodec);
}
