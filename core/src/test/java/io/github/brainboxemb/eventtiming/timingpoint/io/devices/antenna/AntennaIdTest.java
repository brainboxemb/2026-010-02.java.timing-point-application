package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AntennaIdTest {

    @Test
    public void keepsConfiguredIdentityAsValueObject() {
        AntennaId antennaId =
                new AntennaId(" ANT1 ");

        assertEquals("ANT1", antennaId.value());
        assertEquals(
                new AntennaId("ANT1"),
                antennaId);
        assertEquals(
                new AntennaId("ANT1").hashCode(),
                antennaId.hashCode());
        assertEquals("ANT1", antennaId.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankIdentity() {
        new AntennaId(" ");
    }
}
