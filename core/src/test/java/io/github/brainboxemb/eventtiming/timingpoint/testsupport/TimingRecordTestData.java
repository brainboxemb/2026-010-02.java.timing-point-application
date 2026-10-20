package io.github.brainboxemb.eventtiming.timingpoint.testsupport;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

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
        return TimingRecord.automatic(
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
        return TimingRecord.manual(
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
        return TimingRecord.opened(
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
        return TimingRecord.closed(
                nodeId.value().charAt(0),
                sequence,
                locationId.value(),
                effectiveTime.instant().toEpochMilli(),
                recordedAt.instant().toEpochMilli());
    }
}
