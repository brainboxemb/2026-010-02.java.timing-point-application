package io.github.brainboxemb.eventtiming.timingdata;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class TimingDataTest {

    @Test
    public void recordKeyAcceptsFirstAndMaximumSequence() {
        assertEquals(1L, new TimingData.RecordKey(new TimingDataTypes.NodeId("A"), 1L).sequenceNumber());
        assertEquals(
                TimingData.MAX_SEQUENCE_NUMBER,
                new TimingData.RecordKey(
                        new TimingDataTypes.NodeId("A"),
                        TimingData.MAX_SEQUENCE_NUMBER).sequenceNumber());
    }

    @Test(expected = IllegalArgumentException.class)
    public void recordKeyRejectsReservedZeroSequence() {
        new TimingData.RecordKey(new TimingDataTypes.NodeId("A"), 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void recordKeyRejectsSequenceBeyondJsonSafeRange() {
        new TimingData.RecordKey(
                new TimingDataTypes.NodeId("A"),
                TimingData.MAX_SEQUENCE_NUMBER + 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void recordKeyRejectsMissingNodeIdentity() {
        new TimingData.RecordKey(null, 1L);
    }

    @Test
    public void recordKeyUsesSharedNormalizedNodeIdentity() {
        TimingData.RecordKey key = new TimingData.RecordKey(new TimingDataTypes.NodeId(" A "), 1L);
        assertEquals(new TimingDataTypes.NodeId("A"), key.timingNodeId());
    }

    @Test
    public void recordKeyEqualityUsesBothSourceAndSequence() {
        TimingData.RecordKey key = new TimingData.RecordKey(new TimingDataTypes.NodeId("A"), 7L);
        assertEquals(key, new TimingData.RecordKey(new TimingDataTypes.NodeId("A"), 7L));
        assertNotEquals(key, new TimingData.RecordKey(new TimingDataTypes.NodeId("B"), 7L));
        assertNotEquals(key, new TimingData.RecordKey(new TimingDataTypes.NodeId("A"), 8L));
    }
}
