package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;

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
