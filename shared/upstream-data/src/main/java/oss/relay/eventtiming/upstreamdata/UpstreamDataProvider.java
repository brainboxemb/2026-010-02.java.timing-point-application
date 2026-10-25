package oss.relay.eventtiming.upstreamdata;

import oss.relay.eventtiming.timingdata.TimingDataCodec;

/**
 * Typed provider SPI for one concrete UpstreamData representation.
 */
public interface UpstreamDataProvider {
    String id();

    UpstreamDataCodec createCodec(TimingDataCodec timingDataCodec);
}
