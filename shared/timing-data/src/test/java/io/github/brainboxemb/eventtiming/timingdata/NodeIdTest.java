package io.github.brainboxemb.eventtiming.timingdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NodeIdTest {
    @Test
    public void keepsLetterIdentifierValue() {
        TimingDataTypes.NodeId id = new TimingDataTypes.NodeId("A");

        assertEquals("A", id.value());
        assertEquals(new TimingDataTypes.NodeId("A"), id);
        assertEquals("A", id.toString());
    }

    @Test
    public void acceptsLastLetter() {
        assertEquals("Z", new TimingDataTypes.NodeId("Z").value());
    }

    @Test
    public void acceptsNumericIdentifiersOneThroughNine() {
        assertEquals("1", new TimingDataTypes.NodeId("1").value());
        assertEquals("9", new TimingDataTypes.NodeId("9").value());
    }

    @Test
    public void trimsConfigurationWhitespace() {
        assertEquals("A", new TimingDataTypes.NodeId("  A  ").value());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankIdentifier() {
        new TimingDataTypes.NodeId("   ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOldLongIdentifier() {
        new TimingDataTypes.NodeId("TN-01");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLetterAndNumberCombination() {
        new TimingDataTypes.NodeId("A1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLowercaseLetter() {
        new TimingDataTypes.NodeId("a");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZero() {
        new TimingDataTypes.NodeId("0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTwoDigitNumber() {
        new TimingDataTypes.NodeId("10");
    }
}
