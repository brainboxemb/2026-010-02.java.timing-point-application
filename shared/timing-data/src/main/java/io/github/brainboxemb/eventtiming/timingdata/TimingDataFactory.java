package io.github.brainboxemb.eventtiming.timingdata;

/**
 * Stateless construction boundary for the configured concrete TimingData profile.
 *
 * <p>Callers select all state-dependent values before invoking the factory. The
 * factory does not allocate sequence numbers, read TimingNode state, persist
 * records or publish events.</p>
 */
public interface TimingDataFactory {

    TimingData.AutomaticRegistration createAutomaticRegistration(
            Context context,
            TimingDataTypes.RegistrationId registrationId);

    /**
     * Creates an automatic registration record for the requested append-only
     * action.
     *
     * <p>The default keeps existing profile implementations source-compatible:
     * ADD delegates to the original factory method; REV must be explicitly
     * supported by the selected profile.</p>
     */
    default TimingData.AutomaticRegistration createAutomaticRegistration(
            Context context,
            TimingDataTypes.RegistrationId registrationId,
            TimingData.RegistrationAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (action == TimingData.RegistrationAction.ADD) {
            return createAutomaticRegistration(
                    context,
                    registrationId);
        }
        throw new UnsupportedOperationException(
                "TimingData profile does not support automatic REV records");
    }

    TimingData.ManualRegistration createManualRegistration(
            Context context,
            TimingDataTypes.RegistrationId registrationId,
            TimingData.ManualTimeSource timeSource);

    /** See the automatic-registration overload for compatibility semantics. */
    default TimingData.ManualRegistration createManualRegistration(
            Context context,
            TimingDataTypes.RegistrationId registrationId,
            TimingData.ManualTimeSource timeSource,
            TimingData.RegistrationAction action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (action == TimingData.RegistrationAction.ADD) {
            return createManualRegistration(
                    context,
                    registrationId,
                    timeSource);
        }
        throw new UnsupportedOperationException(
                "TimingData profile does not support manual REV records");
    }

    default TimingData.NodeOpen createNodeOpen(Context context) {
        throw new UnsupportedOperationException(
                "TimingData profile does not support NODE_INFO OPEN records");
    }

    default TimingData.NodeClose createNodeClose(Context context) {
        throw new UnsupportedOperationException(
                "TimingData profile does not support NODE_INFO CLOSE records");
    }

    /**
     * Immutable common construction values supplied to every TimingData variant.
     *
     * <p>This is a value-only hand-off into a concrete profile. Stateful policy
     * remains with the owning application/domain component.</p>
     */
    final class Context {
        private final TimingDataTypes.NodeId timingNodeId;
        private final long sequenceNumber;
        private final TimingDataTypes.LocationId locationId;
        private final TimingTimestamp effectiveTime;
        private final TimingTimestamp recordedAt;
        private final TimingData.TagSource tagSource;
        private final TimingData.AutomaticTimeSource registrationTimeSource;

        public Context(
                TimingDataTypes.NodeId timingNodeId,
                long sequenceNumber,
                TimingDataTypes.LocationId locationId,
                TimingTimestamp effectiveTime,
                TimingTimestamp recordedAt) {
            this(timingNodeId, sequenceNumber, locationId, effectiveTime,
                    recordedAt, null, null);
        }

        public Context(
                TimingDataTypes.NodeId timingNodeId,
                long sequenceNumber,
                TimingDataTypes.LocationId locationId,
                TimingTimestamp effectiveTime,
                TimingTimestamp recordedAt,
                TimingData.TagSource tagSource,
                TimingData.AutomaticTimeSource registrationTimeSource) {
            if ((tagSource == null) != (registrationTimeSource == null)) {
                throw new IllegalArgumentException(
                        "tagSrc and timeSrc must be both present or both absent");
            }
            if (timingNodeId == null) {
                throw new IllegalArgumentException("timingNodeId must not be null");
            }
            if (sequenceNumber < 1L || sequenceNumber > TimingData.MAX_SEQUENCE_NUMBER) {
                throw new IllegalArgumentException(
                        "sequenceNumber must be in range 1.."
                                + TimingData.MAX_SEQUENCE_NUMBER);
            }
            if (locationId == null) {
                throw new IllegalArgumentException("locationId must not be null");
            }
            if (effectiveTime == null) {
                throw new IllegalArgumentException("effectiveTime must not be null");
            }
            if (recordedAt == null) {
                throw new IllegalArgumentException("recordedAt must not be null");
            }
            this.timingNodeId = timingNodeId;
            this.sequenceNumber = sequenceNumber;
            this.locationId = locationId;
            this.effectiveTime = effectiveTime;
            this.recordedAt = recordedAt;
            this.tagSource = tagSource;
            this.registrationTimeSource = registrationTimeSource;
        }

        public TimingDataTypes.NodeId timingNodeId() {
            return timingNodeId;
        }

        public long sequenceNumber() {
            return sequenceNumber;
        }

        public TimingDataTypes.LocationId locationId() {
            return locationId;
        }

        public TimingTimestamp effectiveTime() {
            return effectiveTime;
        }

        public TimingTimestamp recordedAt() {
            return recordedAt;
        }

        public TimingData.TagSource tagSource() {
            return tagSource;
        }

        public TimingData.AutomaticTimeSource registrationTimeSource() {
            return registrationTimeSource;
        }
    }
}
