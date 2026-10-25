package oss.relay.eventtiming.timingdata;

import java.util.Objects;

/**
 * One immutable IF-05 timing fact, retained entirely as scalar data.
 *
 * <p>This is the shared record contract for LogBook storage, commands, queries
 * and codecs. The node id is one character, the registration id is packed in
 * one int, effective time is UTC epoch milliseconds, and optional recorded-at
 * metadata uses the same scalar representation when present. No semantic
 * wrapper, timestamp or protocol object is retained.</p>
 *
 * <p>One instance represents exactly one of the four supported record kinds.
 * Fields not applicable to a kind use zero or -1 internally; the factories
 * enforce the valid combinations instead of exposing invalid mutable state.</p>
 */
public final class TimingRecord {
    /** Largest JSON-safe IF-05 sequence value: 2^53 - 1. */
    public static final long MAX_SEQUENCE_NUMBER = 9007199254740991L;

    /** Internal scalar marker for an omitted optional IF-05 recTime. */
    public static final long NO_RECORDED_AT_MILLIS = Long.MIN_VALUE;

    public enum Kind {
        AUTOMATIC_REGISTRATION,
        MANUAL_REGISTRATION,
        NODE_OPEN,
        NODE_CLOSE
    }

    public enum Action {
        ADD,
        REV
    }

    public enum TagSource {
        ANT,
        API
    }

    public enum AutomaticTimeSource {
        OBS,
        API,
        NODE
    }

    public enum ManualTimeSource {
        AUTOMATIC,
        OPERATOR_ENTERED
    }

    private static final Kind[] KINDS = Kind.values();
    private static final Action[] ACTIONS = Action.values();
    private static final TagSource[] TAG_SOURCES = TagSource.values();
    private static final AutomaticTimeSource[] AUTO_TIME_SOURCES = AutomaticTimeSource.values();
    private static final ManualTimeSource[] MANUAL_TIME_SOURCES = ManualTimeSource.values();

    private final long sequenceNumber;
    private final long effectiveTimeMillis;
    private final long recordedAtMillis;
    private final int locationId;
    private final int registrationId;
    private final char nodeId;
    private final byte kind;
    private final byte action;
    private final byte source1;
    private final byte source2;

    private TimingRecord(
            char nodeId,
            long sequenceNumber,
            int locationId,
            long effectiveTimeMillis,
            long recordedAtMillis,
            int registrationId,
            Kind kind,
            byte action,
            byte source1,
            byte source2) {
        if (!isNodeId(nodeId)) {
            throw new IllegalArgumentException("nodeId must be A-Z or 1-9");
        }
        if (sequenceNumber < 1L || sequenceNumber > MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException("sequenceNumber is outside IF-05 range");
        }
        if (locationId < 1) {
            throw new IllegalArgumentException("locationId must be positive");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (kind == Kind.AUTOMATIC_REGISTRATION || kind == Kind.MANUAL_REGISTRATION) {
            if (!isRegistrationId(registrationId)) {
                throw new IllegalArgumentException("invalid packed registrationId");
            }
            if (action < 0 || action >= ACTIONS.length) {
                throw new IllegalArgumentException("invalid registration action");
            }
        } else if (registrationId != 0 || action != -1 || source1 != -1 || source2 != -1) {
            throw new IllegalArgumentException("lifecycle records must not carry registration data");
        }
        if (kind == Kind.AUTOMATIC_REGISTRATION
                && ((source1 == -1 && source2 != -1)
                        || (source1 != -1 && source2 == -1)
                        || source1 >= TAG_SOURCES.length
                        || source2 >= AUTO_TIME_SOURCES.length
                        || source1 < -1 || source2 < -1)) {
            throw new IllegalArgumentException("invalid automatic provenance pair");
        }
        if (kind == Kind.MANUAL_REGISTRATION
                && (source1 < 0 || source1 >= MANUAL_TIME_SOURCES.length || source2 != -1)) {
            throw new IllegalArgumentException("invalid manual time source");
        }

        this.nodeId = nodeId;
        this.sequenceNumber = sequenceNumber;
        this.locationId = locationId;
        this.effectiveTimeMillis = effectiveTimeMillis;
        this.recordedAtMillis = recordedAtMillis;
        this.registrationId = registrationId;
        this.kind = (byte) kind.ordinal();
        this.action = action;
        this.source1 = source1;
        this.source2 = source2;
    }

    /** Automatic provenance is either fully present or fully absent for legacy records. */
    public static TimingRecord createAutomaticRegistration(
            char nodeId,
            long sequenceNumber,
            int locationId,
            long effectiveTimeMillis,
            long recordedAtMillis,
            int packedRegistrationId,
            Action action,
            TagSource tagSource,
            AutomaticTimeSource timeSource) {
        requireAction(action);
        if ((tagSource == null) != (timeSource == null)) {
            throw new IllegalArgumentException("tagSource and timeSource must be both present or absent");
        }
        return new TimingRecord(
                nodeId, sequenceNumber, locationId, effectiveTimeMillis, recordedAtMillis,
                packedRegistrationId, Kind.AUTOMATIC_REGISTRATION, (byte) action.ordinal(),
                nullableOrdinal(tagSource), nullableOrdinal(timeSource));
    }

    /** Manual registrations always retain the time-source code that explains their effective time. */
    public static TimingRecord createManualRegistration(
            char nodeId,
            long sequenceNumber,
            int locationId,
            long effectiveTimeMillis,
            long recordedAtMillis,
            int packedRegistrationId,
            Action action,
            ManualTimeSource timeSource) {
        requireAction(action);
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        return new TimingRecord(
                nodeId, sequenceNumber, locationId, effectiveTimeMillis, recordedAtMillis,
                packedRegistrationId, Kind.MANUAL_REGISTRATION, (byte) action.ordinal(),
                (byte) timeSource.ordinal(), (byte) -1);
    }

    /** NODE_OPEN carries lifecycle timing only and therefore has no registration fields. */
    public static TimingRecord createNodeOpened(
            char nodeId, long sequenceNumber, int locationId,
            long effectiveTimeMillis, long recordedAtMillis) {
        return new TimingRecord(
                nodeId, sequenceNumber, locationId, effectiveTimeMillis, recordedAtMillis,
                0, Kind.NODE_OPEN, (byte) -1, (byte) -1, (byte) -1);
    }

    /** NODE_CLOSE carries lifecycle timing only and therefore has no registration fields. */
    public static TimingRecord createNodeClosed(
            char nodeId, long sequenceNumber, int locationId,
            long effectiveTimeMillis, long recordedAtMillis) {
        return new TimingRecord(
                nodeId, sequenceNumber, locationId, effectiveTimeMillis, recordedAtMillis,
                0, Kind.NODE_CLOSE, (byte) -1, (byte) -1, (byte) -1);
    }

    public char nodeId() {
        return nodeId;
    }

    public long sequenceNumber() {
        return sequenceNumber;
    }

    public int locationId() {
        return locationId;
    }

    public long effectiveTimeMillis() {
        return effectiveTimeMillis;
    }

    public long recordedAtMillis() {
        return recordedAtMillis;
    }

    public boolean hasRecordedAtMillis() {
        return recordedAtMillis != NO_RECORDED_AT_MILLIS;
    }

    /** Packed registration id, or zero for NODE_OPEN/NODE_CLOSE. */
    public int registrationId() {
        return registrationId;
    }

    public Kind kind() {
        return KINDS[kind];
    }

    /** Null for NODE_OPEN/NODE_CLOSE. */
    public Action action() {
        return action < 0 ? null : ACTIONS[action];
    }

    /** Null outside automatic registration or for legacy records. */
    public TagSource tagSource() {
        return kind() == Kind.AUTOMATIC_REGISTRATION && source1 >= 0
                ? TAG_SOURCES[source1] : null;
    }

    /** Null outside automatic registration or for legacy records. */
    public AutomaticTimeSource automaticTimeSource() {
        return kind() == Kind.AUTOMATIC_REGISTRATION && source2 >= 0
                ? AUTO_TIME_SOURCES[source2] : null;
    }

    /** Null outside manual registration. */
    public ManualTimeSource manualTimeSource() {
        return kind() == Kind.MANUAL_REGISTRATION ? MANUAL_TIME_SOURCES[source1] : null;
    }

    private static void requireAction(Action action) {
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
    }

    private static byte nullableOrdinal(Enum<?> value) {
        return value == null ? (byte) -1 : (byte) value.ordinal();
    }

    private static boolean isNodeId(char value) {
        return value >= 'A' && value <= 'Z' || value >= '1' && value <= '9';
    }

    private static boolean isRegistrationId(int value) {
        char type = (char) (value >>> 16);
        int number = value & 0xffff;
        return type >= 'A' && type <= 'Z' && number <= 9999;
    }

    /** Stable IF-05 record identity without retaining semantic wrapper objects. */
    public static final class RecordKey {
        private final char nodeId;
        private final long sequenceNumber;

        public RecordKey(char nodeId, long sequenceNumber) {
            if (!isNodeId(nodeId)) {
                throw new IllegalArgumentException("nodeId must be A-Z or 1-9");
            }
            if (sequenceNumber < 1L || sequenceNumber > MAX_SEQUENCE_NUMBER) {
                throw new IllegalArgumentException(
                        "sequenceNumber must be in range 1.." + MAX_SEQUENCE_NUMBER);
            }
            this.nodeId = nodeId;
            this.sequenceNumber = sequenceNumber;
        }

        public char nodeId() {
            return nodeId;
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
            return nodeId == that.nodeId && sequenceNumber == that.sequenceNumber;
        }

        @Override
        public int hashCode() {
            return Objects.hash(nodeId, sequenceNumber);
        }

        @Override
        public String toString() {
            return nodeId + ":" + sequenceNumber;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TimingRecord)) {
            return false;
        }
        TimingRecord that = (TimingRecord) other;
        return sequenceNumber == that.sequenceNumber
                && effectiveTimeMillis == that.effectiveTimeMillis
                && recordedAtMillis == that.recordedAtMillis
                && locationId == that.locationId
                && registrationId == that.registrationId
                && nodeId == that.nodeId
                && kind == that.kind
                && action == that.action
                && source1 == that.source1
                && source2 == that.source2;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                sequenceNumber, effectiveTimeMillis, recordedAtMillis,
                locationId, registrationId, nodeId, kind, action, source1, source2);
    }
}
