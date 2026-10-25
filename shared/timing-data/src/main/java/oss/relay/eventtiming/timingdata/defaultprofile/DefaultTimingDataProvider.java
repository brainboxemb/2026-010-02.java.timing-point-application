package oss.relay.eventtiming.timingdata.defaultprofile;

import oss.relay.eventtiming.timingdata.TimingDataCodec;
import oss.relay.eventtiming.timingdata.TimingDataProvider;

/** Built-in public/reference IF-05 TimingRecord provider. */
public final class DefaultTimingDataProvider
        implements TimingDataProvider {

    public static final String ID = "reference";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public TimingDataCodec createCodec() {
        return new DefaultTimingDataCodec();
    }
}
