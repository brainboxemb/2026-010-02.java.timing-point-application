package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataProvider;

/** Built-in provider for the public/reference IF-06 JSON representation. */
public final class DefaultUpstreamDataProvider
        implements UpstreamDataProvider {
    public static final String ID = "reference";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public UpstreamDataCodec createCodec(TimingDataCodec timingDataCodec) {
        return new DefaultUpstreamDataCodec(timingDataCodec);
    }
}
