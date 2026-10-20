package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecords;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Scalar timing-record persistence/state operations owned by LogBook.
 *
 * <p>TimingData is materialized only for the still-compatible result-bearing
 * command API. Creation, persistence, retained history and reconstruction all
 * use {@link TimingRecord} directly.</p>
 */
final class LogBookOperations {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookOperations.class);

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

        long now = currentCentisecondMillis();
        commitRecord(
                TimingRecord.opened(
                        nodeId(),
                        state.nextTimingDataSequence(),
                        locationId.value(),
                        now,
                        currentCentisecondMillis()));
    }

    void commitNodeClose(LocationId locationId)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        long now = currentCentisecondMillis();
        commitRecord(
                TimingRecord.closed(
                        nodeId(),
                        state.nextTimingDataSequence(),
                        locationId.value(),
                        now,
                        currentCentisecondMillis()));
    }

    TimingData commitAutomaticRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.TagSource tagSource,
            TimingData.AutomaticTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(effectiveTime != null, "effectiveTime must not be null");
        failIfInvalidArgument(tagSource != null, "tagSource must not be null");
        failIfInvalidArgument(registrationTimeSource != null, "registrationTimeSource must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.automatic(
                nodeId(),
                state.nextTimingDataSequence(),
                locationId.value(),
                centisecondMillis(effectiveTime.instant()),
                currentCentisecondMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.ADD,
                TimingRecord.TagSource.valueOf(tagSource.name()),
                TimingRecord.AutomaticTimeSource.valueOf(registrationTimeSource.name()));
        commitRecord(record);
        return TimingRecords.asTimingData(record);
    }

    TimingData commitManualRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(effectiveTime != null, "effectiveTime must not be null");
        failIfInvalidArgument(registrationTimeSource != null, "registrationTimeSource must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.manual(
                nodeId(),
                state.nextTimingDataSequence(),
                locationId.value(),
                centisecondMillis(effectiveTime.instant()),
                currentCentisecondMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.ADD,
                TimingRecord.ManualTimeSource.valueOf(registrationTimeSource.name()));
        commitRecord(record);
        return TimingRecords.asTimingData(record);
    }

    TimingData revokeAutomaticRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(originalLocationId != null, "originalLocationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(originalTime != null, "originalTime must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.automatic(
                nodeId(),
                state.nextTimingDataSequence(),
                originalLocationId.value(),
                centisecondMillis(originalTime.instant()),
                currentCentisecondMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.REV,
                TimingRecord.TagSource.API,
                TimingRecord.AutomaticTimeSource.API);
        commitRecord(record);
        return TimingRecords.asTimingData(record);
    }

    TimingData revokeManualRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime,
            ManualTimeSource originalTimeSource)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(originalLocationId != null, "originalLocationId must not be null");
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
        failIfInvalidArgument(originalTime != null, "originalTime must not be null");
        failIfInvalidArgument(originalTimeSource != null, "originalTimeSource must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingRecord record = TimingRecord.manual(
                nodeId(),
                state.nextTimingDataSequence(),
                originalLocationId.value(),
                centisecondMillis(originalTime.instant()),
                currentCentisecondMillis(),
                registrationId.packedValue(),
                TimingRecord.Action.REV,
                TimingRecord.ManualTimeSource.valueOf(originalTimeSource.name()));
        commitRecord(record);
        return TimingRecords.asTimingData(record);
    }

    TimingDataPersistence.LoadResult loadPersistedTimingData()
            throws TimingDataPersistence.PersistenceException {
        return timingDataPersistence.load();
    }

    void restorePersistedTimingData(TimingDataPersistence.LoadResult loadResult) {
        failIfInvalidArgument(loadResult != null, "loadResult must not be null");
        for (TimingRecord record : loadResult.timingRecords()) {
            state.addCommittedRecord(record);
        }
    }

    void markTimingDataLoadFailed(Throwable failure) {
        failIfInvalidArgument(failure != null, "failure must not be null");

        String detail = failure.getMessage();
        String message = "Persisted TimingData load failed for " + state.timingNodeId().value();
        if (detail != null && !detail.trim().isEmpty()) {
            message += ": " + detail.trim();
        }
        state.markTimingDataLoadFailed(message);
    }

    private char nodeId() {
        return state.timingNodeId().value().charAt(0);
    }

    private long currentCentisecondMillis() {
        return centisecondMillis(timeSource.now());
    }

    private static long centisecondMillis(Instant instant) {
        failIfInvalidArgument(instant != null, "instant must not be null");
        long epochMillis = instant.toEpochMilli();
        return epochMillis - Math.floorMod(epochMillis, 10L);
    }

    private void ensureTimingDataCommitAvailable() {
        if (timingDataCommitFailure != null) {
            throw new IllegalStateException(
                    "TimingData commit is blocked after an earlier persistence failure",
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
                    record.kind(),
                    record.locationId(),
                    ex);
            throw ex;
        }
        metrics.recordAppendDuration(monotonicClock.nowNanos() - appendStartedNanos);

        try {
            state.addCommittedRecord(record);
        } catch (RuntimeException ex) {
            timingDataCommitFailure = ex;
            LOG.error(
                    "Timing record in-memory commit failed after persistence: "
                            + "node={}, sequence={}, type={}, location={}",
                    record.nodeId(),
                    record.sequenceNumber(),
                    record.kind(),
                    record.locationId(),
                    ex);
            throw ex;
        }

        metrics.recordCommit();
        logCommittedRecord(record);
    }

    private static void logCommittedRecord(TimingRecord record) {
        if (record.kind() == TimingRecord.Kind.AUTOMATIC_REGISTRATION) {
            LOG.info(
                    "Timing record committed: node={}, sequence={}, type={}, registrationId={}, "
                            + "action={}, location={}, effectiveTimeMillis={}, tagSource={}, timeSource={}",
                    record.nodeId(),
                    record.sequenceNumber(),
                    record.kind(),
                    RegistrationId.fromPackedValue(record.registrationId()).value(),
                    record.action(),
                    record.locationId(),
                    record.effectiveTimeMillis(),
                    record.tagSource(),
                    record.automaticTimeSource());
        } else if (record.kind() == TimingRecord.Kind.MANUAL_REGISTRATION) {
            LOG.info(
                    "Timing record committed: node={}, sequence={}, type={}, registrationId={}, "
                            + "action={}, location={}, effectiveTimeMillis={}, timeSource={}",
                    record.nodeId(),
                    record.sequenceNumber(),
                    record.kind(),
                    RegistrationId.fromPackedValue(record.registrationId()).value(),
                    record.action(),
                    record.locationId(),
                    record.effectiveTimeMillis(),
                    record.manualTimeSource());
        } else {
            LOG.info(
                    "Timing record committed: node={}, sequence={}, type={}, location={}, effectiveTimeMillis={}",
                    record.nodeId(),
                    record.sequenceNumber(),
                    record.kind(),
                    record.locationId(),
                    record.effectiveTimeMillis());
        }
    }
}
