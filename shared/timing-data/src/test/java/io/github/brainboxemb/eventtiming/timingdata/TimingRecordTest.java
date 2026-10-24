package io.github.brainboxemb.eventtiming.timingdata;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class TimingRecordTest {

    @Test
    public void automaticRecordRetainsCompactScalarContract() {
        RegistrationId registrationId = new RegistrationId("A0042");

        TimingRecord record = TimingRecord.createAutomaticRegistration(
                'A',
                7L,
                12,
                1790798499123L,
                1790798500000L,
                registrationId.packedValue(),
                TimingRecord.Action.ADD,
                TimingRecord.TagSource.API,
                TimingRecord.AutomaticTimeSource.NODE);

        assertEquals('A', record.nodeId());
        assertEquals(7L, record.sequenceNumber());
        assertEquals(12, record.locationId());
        assertEquals(1790798499123L, record.effectiveTimeMillis());
        assertEquals(1790798500000L, record.recordedAtMillis());
        org.junit.Assert.assertTrue(record.hasRecordedAtMillis());
        assertEquals(registrationId.packedValue(), record.registrationId());
        assertSame(TimingRecord.Kind.AUTOMATIC_REGISTRATION, record.kind());
        assertSame(TimingRecord.Action.ADD, record.action());
        assertSame(TimingRecord.TagSource.API, record.tagSource());
        assertSame(TimingRecord.AutomaticTimeSource.NODE, record.automaticTimeSource());
        assertNull(record.manualTimeSource());
    }

    @Test
    public void missingRecordedAtUsesExplicitScalarAbsenceMarker() {
        TimingRecord record =
                TimingRecord.createNodeOpened(
                        'A',
                        1L,
                        12,
                        1790798499120L,
                        TimingRecord.NO_RECORDED_AT_MILLIS);

        org.junit.Assert.assertFalse(record.hasRecordedAtMillis());
        assertEquals(
                TimingRecord.NO_RECORDED_AT_MILLIS,
                record.recordedAtMillis());
    }

    @Test
    public void manualRecordRetainsActionAndTimeSource() {
        TimingRecord record = TimingRecord.createManualRegistration(
                'A',
                8L,
                12,
                1790798499123L,
                1790798500000L,
                new RegistrationId("A0042").packedValue(),
                TimingRecord.Action.REV,
                TimingRecord.ManualTimeSource.OPERATOR_ENTERED);

        assertSame(TimingRecord.Kind.MANUAL_REGISTRATION, record.kind());
        assertSame(TimingRecord.Action.REV, record.action());
        assertSame(TimingRecord.ManualTimeSource.OPERATOR_ENTERED, record.manualTimeSource());
        assertNull(record.tagSource());
        assertNull(record.automaticTimeSource());
    }

    @Test
    public void lifecycleRecordsCarryNoRegistrationFields() {
        TimingRecord opened = TimingRecord.createNodeOpened(
                'A', 1L, 12, 1790798499120L, 1790798500000L);
        TimingRecord closed = TimingRecord.createNodeClosed(
                'A', 2L, 12, 1790798499130L, 1790798500010L);

        assertSame(TimingRecord.Kind.NODE_OPEN, opened.kind());
        assertSame(TimingRecord.Kind.NODE_CLOSE, closed.kind());
        assertEquals(0, opened.registrationId());
        assertNull(opened.action());
        assertNull(opened.tagSource());
        assertNull(opened.automaticTimeSource());
        assertNull(opened.manualTimeSource());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReservedZeroSequence() {
        TimingRecord.createNodeOpened('A', 0L, 12, 1L, 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSequenceAboveJsonSafeRange() {
        TimingRecord.createNodeOpened(
                'A',
                TimingRecord.MAX_SEQUENCE_NUMBER + 1L,
                12,
                1L,
                1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidNodeId() {
        TimingRecord.createNodeOpened('0', 1L, 12, 1L, 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveLocation() {
        TimingRecord.createNodeOpened('A', 1L, 0, 1L, 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticProvenanceMustBePaired() {
        TimingRecord.createAutomaticRegistration(
                'A',
                1L,
                12,
                1L,
                1L,
                new RegistrationId("A0042").packedValue(),
                TimingRecord.Action.ADD,
                TimingRecord.TagSource.API,
                null);
    }

    @Test
    public void recordKeyAcceptsFirstAndMaximumSequence() {
        assertEquals(
                1L,
                new TimingRecord.RecordKey('A', 1L).sequenceNumber());
        assertEquals(
                TimingRecord.MAX_SEQUENCE_NUMBER,
                new TimingRecord.RecordKey(
                        'A',
                        TimingRecord.MAX_SEQUENCE_NUMBER).sequenceNumber());
    }

    @Test(expected = IllegalArgumentException.class)
    public void recordKeyRejectsReservedZeroSequence() {
        new TimingRecord.RecordKey('A', 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void recordKeyRejectsSequenceBeyondJsonSafeRange() {
        new TimingRecord.RecordKey(
                'A',
                TimingRecord.MAX_SEQUENCE_NUMBER + 1L);
    }

    @Test
    public void recordKeyEqualityUsesNodeAndSequence() {
        TimingRecord.RecordKey key = new TimingRecord.RecordKey('A', 7L);

        assertEquals(key, new TimingRecord.RecordKey('A', 7L));
        assertNotEquals(key, new TimingRecord.RecordKey('B', 7L));
        assertNotEquals(key, new TimingRecord.RecordKey('A', 8L));
    }
}
