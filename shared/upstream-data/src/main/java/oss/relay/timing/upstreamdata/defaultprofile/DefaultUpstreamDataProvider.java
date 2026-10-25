package oss.relay.timing.upstreamdata.defaultprofile;

import oss.relay.timing.timingdata.TimingDataCodec;
import oss.relay.timing.upstreamdata.UpstreamDataCodec;
import oss.relay.timing.upstreamdata.UpstreamDataProvider;

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
