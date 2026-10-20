package io.github.brainboxemb.eventtiming.timingdata;

import java.time.Instant;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

/**
 * Temporary Java-API adapter between the scalar TimingRecord contract and the
 * older semantic TimingData interface.
 *
 * <p>Internal state and codecs should use TimingRecord. A TimingData view is
 * materialized only for callers that still require the Java subtype API. The
 * view retains the TimingRecord itself and does not copy it into another full
 * record representation.</p>
 */
public final class TimingRecords {
    private TimingRecords() {
    }

    /** Copies one legacy semantic value into the scalar shared record contract. */
    public static TimingRecord fromTimingData(TimingData data) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }

        char nodeId = data.timingNodeId().value().charAt(0);
        long sequence = data.sequenceNumber();
        int locationId = data.locationId().value();
        long effectiveTime = data.effectiveTime().instant().toEpochMilli();
        long recordedAt = data.recordedAt().instant().toEpochMilli();

        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration registration =
                    (TimingData.AutomaticRegistration) data;
            return TimingRecord.automatic(
                    nodeId,
                    sequence,
                    locationId,
                    effectiveTime,
                    recordedAt,
                    registration.registrationId().packedValue(),
                    action(registration.action()),
                    tagSource(registration.tagSource()),
                    automaticTimeSource(registration.registrationTimeSource()));
        }
        if (data instanceof TimingData.ManualRegistration) {
            TimingData.ManualRegistration registration =
                    (TimingData.ManualRegistration) data;
            return TimingRecord.manual(
                    nodeId,
                    sequence,
                    locationId,
                    effectiveTime,
                    recordedAt,
                    registration.registrationId().packedValue(),
                    action(registration.action()),
                    manualTimeSource(registration.timeSource()));
        }
        if (data instanceof TimingData.NodeOpen) {
            return TimingRecord.opened(
                    nodeId, sequence, locationId, effectiveTime, recordedAt);
        }
        if (data instanceof TimingData.NodeClose) {
            return TimingRecord.closed(
                    nodeId, sequence, locationId, effectiveTime, recordedAt);
        }
        throw new IllegalArgumentException(
                "Unsupported TimingData type " + data.getClass().getName());
    }

    /** Returns a legacy Java semantic view over one scalar record. */
    public static TimingData asTimingData(TimingRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        switch (record.kind()) {
            case AUTOMATIC_REGISTRATION:
                return new AutomaticRegistrationView(record);
            case MANUAL_REGISTRATION:
                return new ManualRegistrationView(record);
            case NODE_OPEN:
                return new NodeOpenView(record);
            case NODE_CLOSE:
                return new NodeCloseView(record);
            default:
                throw new IllegalStateException("Unsupported TimingRecord kind " + record.kind());
        }
    }

    private abstract static class View implements TimingData {
        private final TimingRecord record;

        private View(TimingRecord record) {
            this.record = record;
        }

        final TimingRecord record() {
            return record;
        }

        @Override
        public final NodeId timingNodeId() {
            return new NodeId(String.valueOf(record.nodeId()));
        }

        @Override
        public final long sequenceNumber() {
            return record.sequenceNumber();
        }

        @Override
        public final LocationId locationId() {
            return new LocationId(record.locationId());
        }

        @Override
        public final TimingTimestamp effectiveTime() {
            return new TimingTimestamp(Instant.ofEpochMilli(record.effectiveTimeMillis()));
        }

        @Override
        public final TimingTimestamp recordedAt() {
            return new TimingTimestamp(Instant.ofEpochMilli(record.recordedAtMillis()));
        }
    }

    private static final class AutomaticRegistrationView
            extends View implements TimingData.AutomaticRegistration {
        private AutomaticRegistrationView(TimingRecord record) {
            super(record);
        }

        @Override
        public RegistrationId registrationId() {
            return RegistrationId.fromPackedValue(record().registrationId());
        }

        @Override
        public TimingData.RegistrationAction action() {
            return legacyAction(record().action());
        }

        @Override
        public TimingData.TagSource tagSource() {
            TimingRecord.TagSource source = record().tagSource();
            return source == null ? null : TimingData.TagSource.valueOf(source.name());
        }

        @Override
        public TimingData.AutomaticTimeSource registrationTimeSource() {
            TimingRecord.AutomaticTimeSource source = record().automaticTimeSource();
            return source == null ? null : TimingData.AutomaticTimeSource.valueOf(source.name());
        }
    }

    private static final class ManualRegistrationView
            extends View implements TimingData.ManualRegistration {
        private ManualRegistrationView(TimingRecord record) {
            super(record);
        }

        @Override
        public RegistrationId registrationId() {
            return RegistrationId.fromPackedValue(record().registrationId());
        }

        @Override
        public TimingData.RegistrationAction action() {
            return legacyAction(record().action());
        }

        @Override
        public TimingData.ManualTimeSource timeSource() {
            return TimingData.ManualTimeSource.valueOf(record().manualTimeSource().name());
        }
    }

    private static final class NodeOpenView extends View implements TimingData.NodeOpen {
        private NodeOpenView(TimingRecord record) {
            super(record);
        }
    }

    private static final class NodeCloseView extends View implements TimingData.NodeClose {
        private NodeCloseView(TimingRecord record) {
            super(record);
        }
    }

    private static TimingRecord.Action action(TimingData.RegistrationAction action) {
        if (action == null) {
            throw new IllegalArgumentException("registration action must not be null");
        }
        return TimingRecord.Action.valueOf(action.name());
    }

    private static TimingData.RegistrationAction legacyAction(TimingRecord.Action action) {
        return TimingData.RegistrationAction.valueOf(action.name());
    }

    private static TimingRecord.TagSource tagSource(TimingData.TagSource source) {
        return source == null ? null : TimingRecord.TagSource.valueOf(source.name());
    }

    private static TimingRecord.AutomaticTimeSource automaticTimeSource(
            TimingData.AutomaticTimeSource source) {
        return source == null
                ? null
                : TimingRecord.AutomaticTimeSource.valueOf(source.name());
    }

    private static TimingRecord.ManualTimeSource manualTimeSource(
            TimingData.ManualTimeSource source) {
        if (source == null) {
            throw new IllegalArgumentException("manual time source must not be null");
        }
        return TimingRecord.ManualTimeSource.valueOf(source.name());
    }
}
