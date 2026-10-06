package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataProvider;

/**
 * Built-in public/reference TimingData provider.
 *
 * <p>The stable provider id matches IF-11. The provider keeps the reference
 * factory and codec paired so runtime selection cannot accidentally combine
 * implementations from different TimingData profiles.</p>
 */
public final class DefaultTimingDataProvider
        implements TimingDataProvider {

    public static final String ID = "reference";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public TimingDataFactory createFactory() {
        return new DefaultTimingDataFactory();
    }

    @Override
    public TimingDataCodec createCodec() {
        return new DefaultTimingDataCodec();
    }
}
