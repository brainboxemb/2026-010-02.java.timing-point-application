package io.github.brainboxemb.eventtiming.timingdata;

import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataProvider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TimingDataProviderTest {

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
}
