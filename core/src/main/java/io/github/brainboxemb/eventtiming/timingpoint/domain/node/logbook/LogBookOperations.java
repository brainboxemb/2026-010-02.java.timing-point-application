package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;

/**
 * TimingData persistence/state operations owned by LogBook.
 *
 * <p>Normal commits execute on the LogBook Data Lane. Persisted-data recovery
 * is invoked by TimingNode.MainTask on the shared node worker and is not a Data
 * Lane operation. This class owns TimingData creation, time capture, durable
 * append/commit and startup reconstruction; command/query queueing and waiting
 * remain in {@link LogBook}.</p>
 */
final class LogBookOperations {
    private static final Logger LOG = LoggerFactory.getLogger(LogBookOperations.class);

    private final LogBookState state;
    private final TimingDataPersistence timingDataPersistence;
    private final TimingDataFactory timingDataFactory;
    private final TimeSource timeSource;
    private final MonotonicClock monotonicClock;
    private final LogBookMetrics metrics;

    /** First failure that prevents later TimingData commits from overtaking it. */
    private Throwable timingDataCommitFailure;

    /** Creates the Data Lane operation owner around passive state and commit dependencies. */
    LogBookOperations(
            LogBookState state,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics) {

        failIfInvalidArgument(state != null, "state must not be null");
        failIfInvalidArgument(timingDataPersistence != null, "timingDataPersistence must not be null");
        failIfInvalidArgument(timingDataFactory != null, "timingDataFactory must not be null");
        failIfInvalidArgument(timeSource != null, "timeSource must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");

        this.state = state;
        this.timingDataPersistence = timingDataPersistence;
        this.timingDataFactory = timingDataFactory;
        this.timeSource = timeSource;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
    }

    /** Commits one NODE_OPEN history record for the node-selected location. */
    void commitNodeOpen(LocationId locationId)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createNodeOpen(
                nextContext(locationId, currentLifecycleTime()));
        commitTimingData(data);
    }

    /** Commits one NODE_CLOSE history record for the node-selected location. */
    void commitNodeClose(LocationId locationId)
            throws TimingDataPersistence.PersistenceException {

        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createNodeClose(
                nextContext(locationId, currentLifecycleTime()));
        commitTimingData(data);
    }

    /**
     * Commits one automatic registration with explicit tag and time sources.
     *
     * <p>The caller determines where the registration and effective time came from;
     * LogBook stores those source values with the TimingData record.</p>
     */
    TimingData commitAutomaticRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.TagSource tagSource,
            TimingData.AutomaticTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {
        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createAutomaticRegistration(
                nextRegistrationContext(
                        locationId,
                        normalizeRegistrationTime(effectiveTime),
                        tagSource,
                        registrationTimeSource),
                registrationId);
        return commitRegistration(data);
    }

    /** Commits one manually entered registration using the supplied effective-time source. */
    TimingData commitManualRegistration(
            LocationId locationId,
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {
        failIfInvalidArgument(locationId != null, "locationId must not be null");
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createManualRegistration(
                nextContext(locationId, normalizeRegistrationTime(effectiveTime)),
                registrationId,
                registrationTimeSource);
        return commitRegistration(data);
    }

    /** Commits a revocation record for one earlier automatic registration. */
    TimingData revokeAutomaticRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime)
            throws TimingDataPersistence.PersistenceException {
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createAutomaticRegistration(
                nextRegistrationContext(
                        originalLocationId,
                        originalTime,
                        TimingData.TagSource.API,
                        TimingData.AutomaticTimeSource.API),
                registrationId,
                TimingData.RegistrationAction.REV);
        return commitRegistration(data);
    }

    /** Commits a revocation record for one earlier manual registration. */
    TimingData revokeManualRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime,
            ManualTimeSource originalTimeSource)
            throws TimingDataPersistence.PersistenceException {
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createManualRegistration(
                nextContext(originalLocationId, originalTime),
                registrationId,
                originalTimeSource,
                TimingData.RegistrationAction.REV);
        return commitRegistration(data);
    }

    /** Reads and validates persisted TimingData without mutating LogBook state. */
    TimingDataPersistence.LoadResult loadPersistedTimingData()
            throws TimingDataPersistence.PersistenceException {

        return timingDataPersistence.load();
    }

    /** Rebuilds in-memory LogBook state from one completed persistence load. */
    void restorePersistedTimingData(
            TimingDataPersistence.LoadResult loadResult) {

        failIfInvalidArgument(loadResult != null, "loadResult must not be null");
        for (TimingData data : loadResult.records()) {
            state.addCommittedTimingData(data);
        }
        state.repairedIncompleteTail(loadResult.repairedIncompleteTail());
    }

    /** Records a LogBook recovery problem after startup loading fails. */
    void markTimingDataLoadFailed(Throwable failure) {

        failIfInvalidArgument(failure != null, "failure must not be null");

        String detail = failure.getMessage();
        String message = "Persisted TimingData load failed for " + state.timingNodeId().value();
        if (detail != null && !detail.trim().isEmpty()) {
            message += ": " + detail.trim();
        }

        state.markTimingDataLoadFailed(message);
    }

    /** Builds the next TimingData context with sequence and recorded time. */
    private Context nextContext(
            LocationId locationId,
            TimingTimestamp effectiveTime) {

        return new Context(
                state.timingNodeId(),
                state.nextTimingDataSequence(),
                locationId,
                effectiveTime,
                currentRecordedTime());
    }

    /** Builds the next automatic-registration context with explicit tag and time sources. */
    private Context nextRegistrationContext(
            LocationId locationId,
            TimingTimestamp effectiveTime,
            TimingData.TagSource tagSource,
            TimingData.AutomaticTimeSource registrationTimeSource) {

        return new Context(
                state.timingNodeId(),
                state.nextTimingDataSequence(),
                locationId,
                effectiveTime,
                currentRecordedTime(),
                tagSource,
                registrationTimeSource);
    }

    /** Captures reference lifecycle time at centisecond resolution. */
    private TimingTimestamp currentLifecycleTime() {

        return atCentisecond(timeSource.now());
    }

    /** Normalizes an accepted registration effective time to centisecond precision. */
    private static TimingTimestamp normalizeRegistrationTime(TimingTimestamp time) {

        failIfInvalidArgument(time != null, "registration time must not be null");
        return atCentisecond(time.instant());
    }

    /** Truncates an Instant to the configured centisecond effective-time precision. */
    private static TimingTimestamp atCentisecond(Instant instant) {

        long nanos = (instant.getNano() / 10_000_000L) * 10_000_000L;
        return new TimingTimestamp(Instant.ofEpochSecond(instant.getEpochSecond(), nanos));
    }

    /** Captures record-creation bookkeeping at millisecond resolution. */
    private TimingTimestamp currentRecordedTime() {

        return new TimingTimestamp(timeSource.now().truncatedTo(ChronoUnit.MILLIS));
    }

    /** Blocks later commits after an earlier persistence or in-memory commit failure. */
    private void ensureTimingDataCommitAvailable() {

        if (timingDataCommitFailure != null) {
            throw new IllegalStateException(
                    "TimingData commit is blocked after an earlier persistence failure",
                    timingDataCommitFailure);
        }
    }

    /** Commits one registration record and returns the committed TimingData value. */
    private TimingData commitRegistration(TimingData data)
            throws TimingDataPersistence.PersistenceException {
        commitTimingData(data);
        return data;
    }

    /** Persists one TimingData record before exposing it as committed in memory. */
    private void commitTimingData(TimingData data)
            throws TimingDataPersistence.PersistenceException {
        failIfIllegalState(data != null, "timingDataFactory returned null");

        metrics.recordAppendAttempt();
        long appendStartedNanos = monotonicClock.nowNanos();
        try {
            timingDataPersistence.append(data);
        } catch (TimingDataPersistence.PersistenceException ex) {

            metrics.recordAppendDuration(monotonicClock.nowNanos() - appendStartedNanos);
            metrics.recordAppendFailure();
            timingDataCommitFailure = ex;
            LOG.error(
                    "TimingData persistence failed: node={}, sequence={}, type={}, location={}",
                    state.timingNodeId().value(),
                    data.sequenceNumber(),
                    timingDataType(data),
                    data.locationId() == null ? "-" : data.locationId().value(),
                    ex);
            throw ex;
        }
        metrics.recordAppendDuration(monotonicClock.nowNanos() - appendStartedNanos);

        try {
            state.addCommittedTimingData(data);
        } catch (RuntimeException ex) {

            timingDataCommitFailure = ex;
            LOG.error(
                    "TimingData in-memory commit failed after persistence: "
                            + "node={}, sequence={}, type={}, location={}",
                    state.timingNodeId().value(),
                    data.sequenceNumber(),
                    timingDataType(data),
                    data.locationId() == null ? "-" : data.locationId().value(),
                    ex);
            throw ex;
        }

        metrics.recordCommit();
        logCommittedTimingData(data);
    }

    /** Logs one successfully committed TimingData record with type-specific context. */
    private void logCommittedTimingData(TimingData data) {

        String type = timingDataType(data);
        String location = data.locationId() == null ? "-" : String.valueOf(data.locationId().value());

        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration registration = (TimingData.AutomaticRegistration) data;
            LOG.info(
                    "TimingData committed: node={}, sequence={}, type={}, registrationId={}, action={}, "
                            + "location={}, effectiveTime={}, tagSource={}, timeSource={}",
                    state.timingNodeId().value(),
                    data.sequenceNumber(),
                    type,
                    registration.registrationId().value(),
                    registration.action(),
                    location,
                    data.effectiveTime(),
                    registration.tagSource(),
                    registration.registrationTimeSource());
        } else if (data instanceof TimingData.ManualRegistration) {

            TimingData.ManualRegistration registration = (TimingData.ManualRegistration) data;
            LOG.info(
                    "TimingData committed: node={}, sequence={}, type={}, registrationId={}, action={}, "
                            + "location={}, effectiveTime={}, timeSource={}",
                    state.timingNodeId().value(),
                    data.sequenceNumber(),
                    type,
                    registration.registrationId().value(),
                    registration.action(),
                    location,
                    data.effectiveTime(),
                    registration.timeSource());
        } else {
            LOG.info(
                    "TimingData committed: node={}, sequence={}, type={}, location={}, effectiveTime={}",
                    state.timingNodeId().value(),
                    data.sequenceNumber(),
                    type,
                    location,
                    data.effectiveTime());
        }
    }

    /** Returns the compact diagnostic record-family label used in commit logging. */
    private static String timingDataType(TimingData data) {

        if (data instanceof TimingData.AutomaticRegistration) {
            return "AUTO_REG";
        }
        if (data instanceof TimingData.ManualRegistration) {
            return "MAN_REG";
        }
        if (data instanceof TimingData.NodeOpen) {
            return "NODE_OPEN";
        }
        if (data instanceof TimingData.NodeClose) {
            return "NODE_CLOSE";
        }
        return data.getClass().getSimpleName();
    }
}
