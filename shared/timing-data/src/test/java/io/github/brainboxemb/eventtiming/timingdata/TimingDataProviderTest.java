package io.github.brainboxemb.eventtiming.timingdata;

import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class TimingDataProviderTest {

    @Test
    public void providerContractExposesStableIdFactoryAndCodec() {
        final TimingDataFactory factory =
                new DefaultTimingDataFactory();
        final TimingDataCodec codec =
                new NoOpCodec();

        TimingDataProvider provider =
                new TimingDataProvider() {
                    @Override
                    public String id() {
                        return "reference";
                    }

                    @Override
                    public TimingDataFactory createFactory() {
                        return factory;
                    }

                    @Override
                    public TimingDataCodec createCodec() {
                        return codec;
                    }
                };

        assertEquals(
                "reference",
                provider.id());
        assertSame(
                factory,
                provider.createFactory());
        assertSame(
                codec,
                provider.createCodec());
    }

    @Test
    public void referenceProviderHasStableIdFactoryAndCodec() {
        TimingDataProvider provider =
                new DefaultTimingDataProvider();

        assertEquals(
                "reference",
                provider.id());
        assertEquals(
                DefaultTimingDataProvider.ID,
                provider.id());
        assertNotNull(
                provider.createFactory());
        assertNotNull(
                provider.createCodec());
    }

    private static final class NoOpCodec
            implements TimingDataCodec {
        @Override
        public byte[] encode(
                TimingData data) {
            throw new UnsupportedOperationException(
                    "not used by this SPI test");
        }

        @Override
        public TimingData decode(
                byte[] encodedRecord) {
            throw new UnsupportedOperationException(
                    "not used by this SPI test");
        }
    }
}
