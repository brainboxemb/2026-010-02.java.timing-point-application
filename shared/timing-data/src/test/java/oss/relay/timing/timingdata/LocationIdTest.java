package oss.relay.timing.timingdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LocationIdTest {
    @Test
    public void keepsPositiveValue() {
        TimingDataTypes.LocationId id = new TimingDataTypes.LocationId(24);

        assertEquals(24, id.value());
        assertEquals(new TimingDataTypes.LocationId(24), id);
        assertEquals("24", id.toString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZero() {
        new TimingDataTypes.LocationId(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeValue() {
        new TimingDataTypes.LocationId(-1);
    }
}
