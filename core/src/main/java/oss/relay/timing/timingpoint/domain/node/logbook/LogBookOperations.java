package oss.relay.timing.timingpoint.domain.node.logbook;

import oss.relay.timing.timingdata.TimingDataTypes.LocationId;
import oss.relay.timing.timingdata.TimingDataTypes.RegistrationId;
import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import oss.relay.timing.timingpoint.platform.environment.MonotonicClock;
import oss.relay.timing.timingpoint.platform.time.TimeSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Scalar timing-record persistence/state operations owned by LogBook.
 *
 * <p>Creation, persistence, retained history, reconstruction and command
 * results all use {@link TimingRecord} directly.</p>
 */
final class LogBookOperations {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookOperations.class);
    private static final DateTimeFormatter LOG_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HHmmssSS")
                    .withZone(ZoneOffset.UTC);

    private final LogBookState state;
    private final TimingDataPersistence timingDataPersistence;
    private final TimeSource timeSource;
    private final MonotonicClock monotonicClock;
    private final LogBookMetrics metrics;

    private Throwable timingDataCommitFailure;

    LogBookOperations(
            LogBookState state,
            TimingDataPersistence timingDataPersistence,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics) {

        failIfInvalidArgument(state != null, "state must not be null");
        failIfInvalidArgument(timingDataPersistence != null, "timingDataPersistence must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");

        this.state = state;
        this.timingDataPersistence = timingDataPersistence;
        this.timeSource = timeSource;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
    }

    void commitNodeOpen(LocationId locationId)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        long nowMillis = currentEpochMillis();
        commitRecord(
                TimingRecord.createNodeOpened(
                        nodeId(),
                        state.nextTimingDataSequence(),
                        locationId.value(),
                        canonicalEffectiveTimeMillis(nowMillis),
                        nowMillis));
    }

    void commitNodeClose(LocationId locationId)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        long nowMillis = currentEpochMillis();
        commitRecord(
                TimingRecord.createNodeClosed(
                        nodeId(),
                        state.nextTimingDataSequence(),
                        locationId.value(),
                        canonicalEffectiveTimeMillis(nowMillis),
                        nowMillis));
    }

    TimingRecord commitAutomaticRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            long effectiveTimeMillis,
            TimingRecord.TagSource tagSource,
            TimingRecord.AutomaticTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(tagSource != null, "tagSource must not be null");
        failIfInvalidArgument(registrationTimeSource != null, "registrationTimeSource must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.createAutomaticRegistration(
                nodeId(),
                state.nextTimingDataSequence(),
                locationId.value(),
                canonicalEffectiveTimeMillis(effectiveTimeMillis),
                currentEpochMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.ADD,
                tagSource,
                registrationTimeSource);
        logRegistrationAccepted(record);
        commitRecord(record);
        return record;
    }

    TimingRecord commitManualRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            long effectiveTimeMillis,
            TimingRecord.ManualTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(registrationTimeSource != null, "registrationTimeSource must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.createManualRegistration(
                nodeId(),
                state.nextTimingDataSequence(),
                locationId.value(),
                canonicalEffectiveTimeMillis(effectiveTimeMillis),
                currentEpochMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.ADD,
                registrationTimeSource);
        logRegistrationAccepted(record);
        commitRecord(record);
        return record;
    }

    TimingRecord revokeAutomaticRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            long originalTimeMillis)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(originalLocationId != null, "originalLocationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.createAutomaticRegistration(
                nodeId(),
                state.nextTimingDataSequence(),
                originalLocationId.value(),
                canonicalEffectiveTimeMillis(originalTimeMillis),
                currentEpochMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.REV,
                TimingRecord.TagSource.API,
                TimingRecord.AutomaticTimeSource.API);
        logRegistrationAccepted(record);
        commitRecord(record);
        return record;
    }

    TimingRecord revokeManualRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            long originalTimeMillis,
            TimingRecord.ManualTimeSource originalTimeSource)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(originalLocationId != null, "originalLocationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(originalTimeSource != null, "originalTimeSource must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.createManualRegistration(
                nodeId(),
                state.nextTimingDataSequence(),
                originalLocationId.value(),
                canonicalEffectiveTimeMillis(originalTimeMillis),
                currentEpochMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.REV,
                originalTimeSource);
        logRegistrationAccepted(record);
        commitRecord(record);
        return record;
    }

    TimingDataPersistence.LoadResult loadLogBookData()
            throws TimingDataPersistence.PersistenceException {
        return timingDataPersistence.load();
    }

    void restoreLogBookData(TimingDataPersistence.LoadResult loadResult) {
        failIfInvalidArgument(loadResult != null, "loadResult must not be null");
        for (TimingRecord record : loadResult.records()) {
            state.addCommittedRecord(record);
        }
    }

    void markTimingDataLoadFailed(Throwable failure) {
        failIfInvalidArgument(failure != null, "failure must not be null");

        String detail = failure.getMessage();
        String message = "Persisted timing-record load failed for " + state.timingNodeId().value();
        if (detail != null && !detail.trim().isEmpty()) {
            message += ": " + detail.trim();
        }
        state.markTimingDataLoadFailed(message);
    }

    private char nodeId() {
        return state.timingNodeId().value().charAt(0);
    }

    /** Record-creation time preserves the millisecond precision required by IF-05 recTime. */
    private long currentEpochMillis() {

        Instant now = timeSource.now();
        failIfInvalidArgument(now != null, "timeSource.now must not return null");
        return now.toEpochMilli();
    }

    /** IF-05 v1 effective time is canonical at centisecond precision even though storage uses epoch milliseconds. */
    private static long canonicalEffectiveTimeMillis(long epochMillis) {
        return epochMillis - Math.floorMod(epochMillis, 10L);
    }

    private void ensureTimingDataCommitAvailable() {
        if (timingDataCommitFailure != null) {
            throw new IllegalStateException(
                    "Timing-record commit is blocked after an earlier persistence failure",
                    timingDataCommitFailure);
        }
    }

    private void commitRecord(TimingRecord record)
            throws TimingDataPersistence.PersistenceException {

        metrics.recordAppendAttempt();
        long appendStartedNanos = monotonicClock.nowNanos();
        try {
            timingDataPersistence.append(record);
        } catch (TimingDataPersistence.PersistenceException ex) {
            metrics.recordAppendDuration(monotonicClock.nowNanos() - appendStartedNanos);
            metrics.recordAppendFailure();
            timingDataCommitFailure = ex;
            LOG.error(
                    "Timing record persistence failed: node={}, sequence={}, type={}, location={}",
                    record.nodeId(),
                    record.sequenceNumber(),
                    logType(record.kind()),
                    record.locationId(),
                    ex);
            throw ex;
        }
        metrics.recordAppendDuration(monotonicClock.nowNanos() - appendStartedNanos);
        logPersistedRecord(record);

        try {
            state.addCommittedRecord(record);
        } catch (RuntimeException ex) {
            timingDataCommitFailure = ex;
            LOG.error(
                    "Timing record in-memory commit failed after persistence: "
                            + "node={}, sequence={}, type={}, location={}",
                    record.nodeId(),
                    record.sequenceNumber(),
                    logType(record.kind()),
                    record.locationId(),
                    ex);
            throw ex;
        }

        metrics.recordCommit();
    }

    /** Formats an absolute timing value as compact UTC HHMMSShh for operator logs. */
    private static String logTime(long epochMillis) {
        return LOG_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    /** Returns the compact IF-05-style record type used in operator logs. */
    private static String logType(TimingRecord.Kind kind) {
        switch (kind) {
            case AUTOMATIC_REGISTRATION:
                return "AUTO_REG";
            case MANUAL_REGISTRATION:
                return "MAN_REG";
            case NODE_OPEN:
                return "OPEN";
            case NODE_CLOSE:
                return "CLOSE";
            default:
                throw new IllegalArgumentException("Unsupported TimingRecord kind " + kind);
        }
    }

    /** Logs one registration after semantic validation and source-sequence allocation. */
    private static void logRegistrationAccepted(TimingRecord record) {
        boolean automatic =
                record.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION;
        failIfInvalidArgument(
                automatic || record.kind() == TimingRecord.Kind.MANUAL_REGISTRATION,
                "registration log requires a registration TimingRecord");

        Object tagSource =
                automatic
                        ? record.tagSource()
                        : "-";
        Object timeSource =
                automatic
                        ? record.automaticTimeSource()
                        : record.manualTimeSource();

        LOG.info(
                "Registration accepted: node={}, registrationId={}, action={}, location={}, "
                        + "effectiveTime={}, type={}, tagSource={}, timeSource={}",
                record.nodeId(),
                RegistrationId.fromPackedValue(record.registrationId()).value(),
                record.action(),
                record.locationId(),
                logTime(record.effectiveTimeMillis()),
                logType(record.kind()),
                tagSource,
                timeSource);
    }

    /** Logs the durable append boundary before the record is exposed in current LogBook state. */
    private static void logPersistedRecord(TimingRecord record) {
        if (record.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION
                || record.kind() == TimingRecord.Kind.MANUAL_REGISTRATION) {
            LOG.info(
                    "LogBook persisted: node={}, sequence={}, type={}, registrationId={}, "
                            + "action={}, location={}",
                    record.nodeId(),
                    record.sequenceNumber(),
                    logType(record.kind()),
                    RegistrationId.fromPackedValue(record.registrationId()).value(),
                    record.action(),
                    record.locationId());
            return;
        }

        LOG.info(
                "LogBook persisted: node={}, sequence={}, type={}, location={}",
                record.nodeId(),
                record.sequenceNumber(),
                logType(record.kind()),
                record.locationId());
    }
}
