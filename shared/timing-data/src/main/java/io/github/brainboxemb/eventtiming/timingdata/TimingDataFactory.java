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

    TimingData.ManualRegistration createManualRegistration(
            Context context,
            TimingDataTypes.RegistrationId registrationId,
            TimingData.ManualTimeSource timeSource);

    TimingData.NodeOpen createNodeOpen(Context context);

    TimingData.NodeClose createNodeClose(Context context);

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

        public Context(
                TimingDataTypes.NodeId timingNodeId,
                long sequenceNumber,
                TimingDataTypes.LocationId locationId,
                TimingTimestamp effectiveTime,
                TimingTimestamp recordedAt) {
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
    }
}
