package io.github.brainboxemb.eventtiming.upstreamdata;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;

/**
 * Typed provider SPI for one concrete UpstreamData representation.
 */
public interface UpstreamDataProvider {
    String id();

    UpstreamDataCodec createCodec(TimingDataCodec timingDataCodec);
}
