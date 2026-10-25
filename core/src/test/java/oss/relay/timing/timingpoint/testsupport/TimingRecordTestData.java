package oss.relay.timing.timingpoint.testsupport;

import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.NodeId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingdata.TimingTimestamp;

/** Test-only helpers for concise scalar TimingRecord fixtures. */
public final class TimingRecordTestData {

    private TimingRecordTestData() {
    }

    public static TimingRecord automatic(
            NodeId nodeId,
            long sequence,
            LocationId locationId,
            TimingTimestamp effectiveTime,
            TimingTimestamp recordedAt) {
        return automatic(
                nodeId,
                sequence,
                locationId,
                effectiveTime,
                recordedAt,
                new RegistrationId("A0001"),
                TimingRecord.Action.ADD,
                null,
                null);
    }

    public static TimingRecord automatic(
            NodeId nodeId,
            long sequence,
            LocationId locationId,
            TimingTimestamp effectiveTime,
            TimingTimestamp recordedAt,
            RegistrationId registrationId,
            TimingRecord.Action action,
            TimingRecord.TagSource tagSource,
            TimingRecord.AutomaticTimeSource timeSource) {
        return TimingRecord.createAutomaticRegistration(
                nodeId.value().charAt(0),
                sequence,
                locationId.value(),
                effectiveTime.instant().toEpochMilli(),
                recordedAt.instant().toEpochMilli(),
                registrationId.packedValue(),
                action,
                tagSource,
                timeSource);
    }

    public static TimingRecord manual(
            NodeId nodeId,
            long sequence,
            LocationId locationId,
            TimingTimestamp effectiveTime,
            TimingTimestamp recordedAt,
            RegistrationId registrationId,
            TimingRecord.ManualTimeSource timeSource) {
        return manual(
                nodeId,
                sequence,
                locationId,
                effectiveTime,
                recordedAt,
                registrationId,
                timeSource,
                TimingRecord.Action.ADD);
    }

    public static TimingRecord manual(
            NodeId nodeId,
            long sequence,
            LocationId locationId,
            TimingTimestamp effectiveTime,
            TimingTimestamp recordedAt,
            RegistrationId registrationId,
            TimingRecord.ManualTimeSource timeSource,
            TimingRecord.Action action) {
        return TimingRecord.createManualRegistration(
                nodeId.value().charAt(0),
                sequence,
                locationId.value(),
                effectiveTime.instant().toEpochMilli(),
                recordedAt.instant().toEpochMilli(),
                registrationId.packedValue(),
                action,
                timeSource);
    }

    public static TimingRecord opened(
            NodeId nodeId,
            long sequence,
            LocationId locationId,
            TimingTimestamp effectiveTime,
            TimingTimestamp recordedAt) {
        return TimingRecord.createNodeOpened(
                nodeId.value().charAt(0),
                sequence,
                locationId.value(),
                effectiveTime.instant().toEpochMilli(),
                recordedAt.instant().toEpochMilli());
    }

    public static TimingRecord closed(
            NodeId nodeId,
            long sequence,
            LocationId locationId,
            TimingTimestamp effectiveTime,
            TimingTimestamp recordedAt) {
        return TimingRecord.createNodeClosed(
                nodeId.value().charAt(0),
                sequence,
                locationId.value(),
                effectiveTime.instant().toEpochMilli(),
                recordedAt.instant().toEpochMilli());
    }
}
