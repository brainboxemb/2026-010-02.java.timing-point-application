package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AntennaIdTest {

    @Test
    public void keepsConfiguredIdentityAsValueObject() {
        AntennaId antennaId = new AntennaId(" 1 ");

        assertEquals("1", antennaId.value());
        assertEquals(new AntennaId("1"), antennaId);
        assertEquals(new AntennaId("1").hashCode(), antennaId.hashCode());
        assertEquals("1", antennaId.toString());
    }

    @Test
    public void acceptsLastAntennaId() {
        assertEquals("9", new AntennaId("9").value());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankIdentity() {
        new AntennaId(" ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZero() {
        new AntennaId("0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOldPrefixedIdentity() {
        new AntennaId("ANT1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTwoDigits() {
        new AntennaId("10");
    }
}
