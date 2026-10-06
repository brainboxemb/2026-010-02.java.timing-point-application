package io.github.brainboxemb.eventtiming.eventdata;

import io.github.brainboxemb.eventtiming.eventdata.defaultprofile.DefaultEventDataProvider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EventDataProviderTest {

    @Test
    public void referenceProviderHasStableIdAndCreatesEmptyProfile() {
        EventDataProvider provider =
                new DefaultEventDataProvider();

        assertEquals(
                "reference",
                provider.id());
        assertEquals(
                DefaultEventDataProvider.ID,
                provider.id());
        assertTrue(
                provider.createEventData().isEmpty());
    }
}
