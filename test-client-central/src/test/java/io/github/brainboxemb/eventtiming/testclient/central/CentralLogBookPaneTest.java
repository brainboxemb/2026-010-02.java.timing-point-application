package io.github.brainboxemb.eventtiming.testclient.central;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralLogBookPaneTest {

    @Test
    void projectsAutomaticRecordWithExistingIf05PresentationSemantics() {
        long millis =
                Instant.parse("2026-10-24T14:30:00.123Z")
                        .toEpochMilli();
        TimingRecord record =
                TimingRecord.createAutomaticRegistration(
                        'A',
                        1186L,
                        7,
                        millis,
                        millis,
                        new RegistrationId("A0001").packedValue(),
                        TimingRecord.Action.ADD,
                        TimingRecord.TagSource.API,
                        TimingRecord.AutomaticTimeSource.API);

        CentralTimingDataInfo view =
                CentralLogBookPane.toInfo(record);

        assertEquals("A", view.timingNodeId());
        assertEquals(1186L, view.sequenceNumber());
        assertEquals(7, view.locationId());
        assertEquals("AUTO_REG", view.recordType());
        assertEquals("A0001", view.registrationId());
        assertEquals(java.util.List.of("ADD"), view.codes());
        assertTrue(view.rawJson().contains("\"recType\":\"AUTO_REG\""));
        assertTrue(view.rawJson().contains("\"seqNr\":1186"));
    }

    @Test
    void projectsLifecycleRecordAsNodeInfoWithOpenCode() {
        long millis =
                Instant.parse("2026-10-24T14:31:00Z")
                        .toEpochMilli();
        TimingRecord record =
                TimingRecord.createNodeOpened(
                        'B',
                        42L,
                        3,
                        millis,
                        millis);

        CentralTimingDataInfo view =
                CentralLogBookPane.toInfo(record);

        assertEquals("B", view.timingNodeId());
        assertEquals("NODE_INFO", view.recordType());
        assertEquals(java.util.List.of("OPEN"), view.codes());
        assertEquals(null, view.registrationId());
    }
}
