package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Problem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ProblemCode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ProblemSeverity;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkState;

/**
 * State-changing LogBook operations executed on the TimingNode Data Lane.
 *
 * <p>This class owns TimingData creation, time capture, durable append/commit,
 * startup reconstruction and the domain decisions behind LogBook commands.
 * Mutable facts and committed in-memory history remain in {@link LogBookState};
 * queueing/waiting remains in {@link LogBookDataLane}.</p>
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

        checkArgument(state != null, "state must not be null");
        checkArgument(timingDataPersistence != null, "timingDataPersistence must not be null");
        checkArgument(timingDataFactory != null, "timingDataFactory must not be null");
        checkArgument(timeSource != null, "timeSource must not be null");
        checkArgument(monotonicClock != null, "monotonicClock must not be null");
        checkArgument(metrics != null, "metrics must not be null");

        this.state = state;
        this.timingDataPersistence = timingDataPersistence;
        this.timingDataFactory = timingDataFactory;
        this.timeSource = timeSource;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
    }

    /** Opens the node at the requested location and commits the lifecycle record first. */
    OpenResult open(LocationId newLocationId) throws TimingDataPersistence.PersistenceException {

        checkArgument(newLocationId != null, "locationId must not be null");
        state.ensureOperational();
        if (state.state() == State.OPEN) {
            return OpenResult.ALREADY_OPEN;
        }
        ensureTimingDataCommitAvailable();

        TimingTimestamp transitionTime = currentLifecycleTime();
        TimingData data = timingDataFactory.createNodeOpen(
                nextLifecycleContext(newLocationId, transitionTime));
        commitTimingData(data);

        state.markOpen(newLocationId);
        return OpenResult.OPENED;
    }

    /** Closes the node and durably commits its lifecycle record before changing state. */
    CloseResult close() throws TimingDataPersistence.PersistenceException {

        state.ensureOperational();
        if (state.state() == State.CLOSED) {
            return CloseResult.ALREADY_CLOSED;
        }
        ensureTimingDataCommitAvailable();

        TimingTimestamp transitionTime = currentLifecycleTime();
        TimingData data = timingDataFactory.createNodeClose(
                nextLifecycleContext(state.locationId(), transitionTime));
        commitTimingData(data);

        state.markClosed();
        return CloseResult.CLOSED;
    }

    /** Commits one antenna-originated automatic registration. */
    RegistrationResult addAutomaticRegistration(
            RegistrationId registrationId, TimingTimestamp time)
            throws TimingDataPersistence.PersistenceException {
        return commitAutomaticRegistration(
                registrationId,
                time,
                TimingData.TagSource.ANT,
                TimingData.AutomaticTimeSource.OBS);
    }

    /** Commits one IF-03 simulation registration with an API-supplied effective time. */
    RegistrationResult simulateAutomaticRegistration(
            RegistrationId registrationId, TimingTimestamp time)
            throws TimingDataPersistence.PersistenceException {
        return commitAutomaticRegistration(
                registrationId,
                time,
                TimingData.TagSource.API,
                TimingData.AutomaticTimeSource.API);
    }

    /** Commits one IF-03 simulation registration using current node time. */
    RegistrationResult simulateAutomaticRegistrationNow(RegistrationId registrationId)
            throws TimingDataPersistence.PersistenceException {
        return commitAutomaticRegistration(
                registrationId,
                atCentisecond(timeSource.now()),
                TimingData.TagSource.API,
                TimingData.AutomaticTimeSource.NODE);
    }

    /** Commits one automatic registration after validating node and commit availability. */
    private RegistrationResult commitAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            TimingData.TagSource tagSource,
            TimingData.AutomaticTimeSource timeProvenance)
            throws TimingDataPersistence.PersistenceException {
        state.ensureOperational();
        if (state.state() != State.OPEN) {
            return RegistrationResult.nodeNotOpen();
        }
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createAutomaticRegistration(
                nextRegistrationContext(
                        state.locationId(),
                        normalizeRegistrationTime(effectiveTime),
                        tagSource,
                        timeProvenance),
                registrationId);
        return commitRegistration(data);
    }

    /** Commits one manually entered registration using the supplied effective-time source. */
    RegistrationResult commitManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {
        state.ensureOperational();
        if (state.state() != State.OPEN) {
            return RegistrationResult.nodeNotOpen();
        }
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createManualRegistration(
                nextRegistrationContext(normalizeRegistrationTime(effectiveTime)),
                registrationId,
                registrationTimeSource);
        return commitRegistration(data);
    }

    /** Commits a revocation record for one earlier automatic registration. */
    RegistrationResult revokeAutomaticRegistration(
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
    RegistrationResult revokeManualRegistration(
            LocationId originalLocationId,
            RegistrationId registrationId,
            TimingTimestamp originalTime,
            ManualTimeSource originalTimeSource)
            throws TimingDataPersistence.PersistenceException {
        state.ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createManualRegistration(
                nextRegistrationContext(originalLocationId, originalTime),
                registrationId,
                originalTimeSource,
                TimingData.RegistrationAction.REV);
        return commitRegistration(data);
    }

    /** Rebuilds committed in-memory history from durable TimingData during activation. */
    TimingDataPersistence.LoadResult loadPersistedTimingData()
            throws TimingDataPersistence.PersistenceException {
        TimingDataPersistence.LoadResult loadResult = timingDataPersistence.load();
        for (TimingData data : loadResult.records()) {
            state.addCommittedTimingData(data);
        }
        state.timingDataTailRepaired(loadResult.repairedIncompleteTail());
        return loadResult;
    }

    /** Moves passive node state to ERROR after startup recovery failed. */
    void markTimingDataLoadFailed(Throwable failure) {

        checkArgument(failure != null, "failure must not be null");

        String detail = failure.getMessage();
        String message = "Persisted TimingData load failed for " + state.timingNodeId().value();
        if (detail != null && !detail.trim().isEmpty()) {
            message += ": " + detail.trim();
        }

        Problem problem = new Problem(
                ProblemCode.TIMING_DATA_LOAD_FAILED,
                ProblemSeverity.ERROR,
                message);
        state.markTimingDataLoadFailed(problem);
    }

    /** Builds the next registration context using the node's current location. */
    private Context nextRegistrationContext(TimingTimestamp effectiveTime) {

        return nextRegistrationContext(state.locationId(), effectiveTime);
    }

    /** Builds the next registration context with explicit location and recorded time. */
    private Context nextRegistrationContext(
            LocationId recordLocationId, TimingTimestamp effectiveTime) {

        return new Context(
                state.timingNodeId(),
                state.nextTimingDataSequence(),
                recordLocationId,
                effectiveTime,
                currentRecordedTime());
    }

    /** Builds the next automatic-registration context including source provenance. */
    private Context nextRegistrationContext(
            LocationId recordLocationId,
            TimingTimestamp effectiveTime,
            TimingData.TagSource tagSource,
            TimingData.AutomaticTimeSource timeProvenance) {

        return new Context(
                state.timingNodeId(),
                state.nextTimingDataSequence(),
                recordLocationId,
                effectiveTime,
                currentRecordedTime(),
                tagSource,
                timeProvenance);
    }

    /** Builds the next lifecycle-record context at the supplied effective time. */
    private Context nextLifecycleContext(
            LocationId lifecycleLocation, TimingTimestamp effectiveTime) {

        return new Context(
                state.timingNodeId(),
                state.nextTimingDataSequence(),
                lifecycleLocation,
                effectiveTime,
                currentRecordedTime());
    }

    /** Captures reference lifecycle time at centisecond resolution. */
    private TimingTimestamp currentLifecycleTime() {

        return atCentisecond(timeSource.now());
    }

    /** Normalizes an accepted registration effective time to centisecond precision. */
    private static TimingTimestamp normalizeRegistrationTime(TimingTimestamp time) {

        checkArgument(time != null, "registration time must not be null");
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

    /** Commits one registration record and wraps the committed value in its domain result. */
    private RegistrationResult commitRegistration(TimingData data)
            throws TimingDataPersistence.PersistenceException {
        commitTimingData(data);
        return RegistrationResult.committed(data);
    }

    /** Persists one TimingData record before exposing it as committed in memory. */
    private void commitTimingData(TimingData data)
            throws TimingDataPersistence.PersistenceException {
        checkState(data != null, "timingDataFactory returned null");

        metrics.recordAppendAttempt();
        long appendStartedNanos = monotonicClock.nowNanos();
        try {
            timingDataPersistence.append(data);
        } catch (TimingDataPersistence.PersistenceException ex) {

            metrics.recordAppendDuration(monotonicClock.nowNanos() - appendStartedNanos);
            metrics.recordAppendFailure();
            timingDataCommitFailure = ex;
            throw ex;
        }
        metrics.recordAppendDuration(monotonicClock.nowNanos() - appendStartedNanos);

        try {
            state.addCommittedTimingData(data);
        } catch (RuntimeException ex) {

            timingDataCommitFailure = ex;
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
