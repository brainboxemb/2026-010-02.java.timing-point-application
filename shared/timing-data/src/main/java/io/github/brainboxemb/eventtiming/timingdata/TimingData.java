package io.github.brainboxemb.eventtiming.timingdata;

import java.util.Objects;

/**
 * Common immutable TimingData contract shared by all concrete profiles.
 *
 * <p>The top-level interface exposes the common IF-05 envelope. Small semantic
 * types that only make sense as part of TimingData are grouped here so consumers
 * can discover the public model from one place instead of navigating many
 * one-type source files.</p>
 *
 * <p>Typical usage:</p>
 *
 * <pre>{@code
 * TimingData data = ...;
 *
 * if (data instanceof TimingData.ManualRegistration) {
 *     TimingData.ManualRegistration manual =
 *             (TimingData.ManualRegistration) data;
 *     TimingDataTypes.RegistrationId registrationId = manual.registrationId();
 *     TimingData.ManualTimeSource source = manual.timeSource();
 * }
 * }</pre>
 */
public interface TimingData {
    /** Largest JSON-safe IF-05 sequence value: 2^53 - 1. */
    long MAX_SEQUENCE_NUMBER = 9007199254740991L;

    TimingDataTypes.NodeId timingNodeId();

    long sequenceNumber();

    TimingDataTypes.LocationId locationId();

    TimingTimestamp effectiveTime();

    TimingTimestamp recordedAt();

    /**
     * Type-safe semantic contract for one automatic/tag registration.
     *
     * <p>The effective time is the accepted observed time by definition, so no
     * separate origin or time-source property is required on this interface.</p>
     */
    interface AutomaticRegistration extends TimingData {
        TimingDataTypes.RegistrationId registrationId();
    }

    /** Type-safe semantic contract for one successful CLOSED -> OPEN transition. */
    interface NodeOpen extends TimingData {
    }

    /** Type-safe semantic contract for one successful OPEN -> CLOSED transition. */
    interface NodeClose extends TimingData {
    }

    /** Type-safe semantic contract for one manual registration. */
    interface ManualRegistration extends TimingData {
        TimingDataTypes.RegistrationId registrationId();

        ManualTimeSource timeSource();
    }

    /** Source of the effective time selected for a manual registration. */
    enum ManualTimeSource {
        SYSTEM_ASSIGNED,
        OPERATOR_ENTERED
    }

    /**
     * Stable IF-05 record identity: serialized TimingNode identity plus source
     * sequence number.
     *
     * <p>The record key uses the same shared TimingDataTypes.NodeId value type as the
     * TimingData envelope. JSON serialization remains the public string value.</p>
     */
    final class RecordKey {
        private final TimingDataTypes.NodeId timingNodeId;
        private final long sequenceNumber;

        public RecordKey(TimingDataTypes.NodeId timingNodeId, long sequenceNumber) {
            if (timingNodeId == null) {
                throw new IllegalArgumentException("timingNodeId must not be null");
            }
            if (sequenceNumber < 1L || sequenceNumber > MAX_SEQUENCE_NUMBER) {
                throw new IllegalArgumentException(
                        "sequenceNumber must be in range 1.." + MAX_SEQUENCE_NUMBER);
            }
            this.timingNodeId = timingNodeId;
            this.sequenceNumber = sequenceNumber;
        }

        public TimingDataTypes.NodeId timingNodeId() {
            return timingNodeId;
        }

        public long sequenceNumber() {
            return sequenceNumber;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof RecordKey)) {
                return false;
            }
            RecordKey that = (RecordKey) other;
            return sequenceNumber == that.sequenceNumber
                    && timingNodeId.equals(that.timingNodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timingNodeId, sequenceNumber);
        }

        @Override
        public String toString() {
            return timingNodeId + ":" + sequenceNumber;
        }
    }
}
