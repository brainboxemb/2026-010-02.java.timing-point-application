package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.time.Instant;

/**
 * Default/reference profile TimingData factory.
 *
 * <p>Concrete default records retain scalar values only. Absolute times are
 * stored as UTC epoch milliseconds in primitive longs and reconstructed with
 * standard Instant conversion only at the TimingData API boundary.</p>
 */
public final class DefaultTimingDataFactory implements TimingDataFactory {
    private static final TimingData.RegistrationAction[] ACTIONS =
            TimingData.RegistrationAction.values();
    private static final TimingData.TagSource[] TAG_SOURCES =
            TimingData.TagSource.values();
    private static final TimingData.AutomaticTimeSource[] AUTOMATIC_TIME_SOURCES =
            TimingData.AutomaticTimeSource.values();
    private static final TimingData.ManualTimeSource[] MANUAL_TIME_SOURCES =
            TimingData.ManualTimeSource.values();

    @Override
    public TimingData.AutomaticRegistration createAutomaticRegistration(
            Context context,
            RegistrationId registrationId) {

        return createAutomaticRegistration(
                context,
                registrationId,
                TimingData.RegistrationAction.ADD);
    }

    @Override
    public TimingData.AutomaticRegistration createAutomaticRegistration(
            Context context,
            RegistrationId registrationId,
            TimingData.RegistrationAction action) {

        return new AutomaticRegistration(
                context,
                registrationId,
                requireAction(action));
    }

    @Override
    public TimingData.ManualRegistration createManualRegistration(
            Context context,
            RegistrationId registrationId,
            TimingData.ManualTimeSource timeSource) {

        return createManualRegistration(
                context,
                registrationId,
                timeSource,
                TimingData.RegistrationAction.ADD);
    }

    @Override
    public TimingData.ManualRegistration createManualRegistration(
            Context context,
            RegistrationId registrationId,
            TimingData.ManualTimeSource timeSource,
            TimingData.RegistrationAction action) {

        return new ManualRegistration(
                context,
                registrationId,
                requireManualTimeSource(timeSource),
                requireAction(action));
    }

    @Override
    public TimingData.NodeOpen createNodeOpen(Context context) {

        return new NodeOpen(context);
    }

    @Override
    public TimingData.NodeClose createNodeClose(Context context) {

        return new NodeClose(context);
    }

    /** Common scalar IF-05 envelope retained by every default record. */
    private abstract static class Value implements TimingData {
        private final long sequenceNumber;
        private final long effectiveTime;
        private final long recordedAt;
        private final int locationId;
        private final char timingNodeId;

        private Value(Context context) {
            Context value = requireContext(context);
            timingNodeId = value.timingNodeId().value().charAt(0);
            sequenceNumber = value.sequenceNumber();
            locationId = value.locationId().value();
            effectiveTime = value.effectiveTime().instant().toEpochMilli();
            recordedAt = value.recordedAt().instant().toEpochMilli();
        }

        @Override
        public final NodeId timingNodeId() {

            return new NodeId(String.valueOf(timingNodeId));
        }

        @Override
        public final long sequenceNumber() {

            return sequenceNumber;
        }

        @Override
        public final LocationId locationId() {

            return new LocationId(locationId);
        }

        @Override
        public final TimingTimestamp effectiveTime() {

            return new TimingTimestamp(Instant.ofEpochMilli(effectiveTime));
        }

        @Override
        public final TimingTimestamp recordedAt() {

            return new TimingTimestamp(Instant.ofEpochMilli(recordedAt));
        }
    }

    /** Scalar registration fields shared by automatic and manual records. */
    private abstract static class Registration extends Value {
        private final int registrationId;
        private final byte action;

        private Registration(
                Context context,
                RegistrationId registrationId,
                TimingData.RegistrationAction action) {
            super(context);
            this.registrationId = requireRegistrationId(registrationId).packedValue();
            this.action = (byte) requireAction(action).ordinal();
        }

        public final RegistrationId registrationId() {

            return RegistrationId.fromPackedValue(registrationId);
        }

        public final TimingData.RegistrationAction action() {

            return ACTIONS[action];
        }
    }

    private static final class AutomaticRegistration
            extends Registration
            implements TimingData.AutomaticRegistration {
        private final byte tagSource;
        private final byte registrationTimeSource;

        private AutomaticRegistration(
                Context context,
                RegistrationId registrationId,
                TimingData.RegistrationAction action) {
            super(
                    context,
                    registrationId,
                    action);
            tagSource = nullableOrdinal(context.tagSource());
            registrationTimeSource = nullableOrdinal(context.registrationTimeSource());
        }

        @Override
        public TimingData.TagSource tagSource() {

            return tagSource < 0 ? null : TAG_SOURCES[tagSource];
        }

        @Override
        public TimingData.AutomaticTimeSource registrationTimeSource() {

            return registrationTimeSource < 0
                    ? null
                    : AUTOMATIC_TIME_SOURCES[registrationTimeSource];
        }
    }

    private abstract static class Lifecycle extends Value {
        private Lifecycle(Context context) {
            super(context);
        }
    }

    private static final class NodeOpen
            extends Lifecycle
            implements TimingData.NodeOpen {

        private NodeOpen(Context context) {
            super(context);
        }
    }

    private static final class NodeClose
            extends Lifecycle
            implements TimingData.NodeClose {

        private NodeClose(Context context) {
            super(context);
        }
    }

    private static final class ManualRegistration
            extends Registration
            implements TimingData.ManualRegistration {
        private final byte timeSource;

        private ManualRegistration(
                Context context,
                RegistrationId registrationId,
                TimingData.ManualTimeSource timeSource,
                TimingData.RegistrationAction action) {
            super(
                    context,
                    registrationId,
                    action);
            this.timeSource = (byte) timeSource.ordinal();
        }

        @Override
        public TimingData.ManualTimeSource timeSource() {

            return MANUAL_TIME_SOURCES[timeSource];
        }
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return context;
    }

    private static RegistrationId requireRegistrationId(
            RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        return registrationId;
    }

    private static TimingData.RegistrationAction requireAction(
            TimingData.RegistrationAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        return action;
    }

    private static TimingData.ManualTimeSource requireManualTimeSource(
            TimingData.ManualTimeSource timeSource) {
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        return timeSource;
    }

    private static byte nullableOrdinal(Enum<?> value) {

        return value == null ? (byte) -1 : (byte) value.ordinal();
    }
}
