package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Problem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ProblemCode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.ProblemSeverity;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Passive mutable state behind one LogBook component.
 *
 * <p>This object contains lifecycle state, LogBook state and commit decisions. It has no queue,
 * timeout or event-delivery responsibility; those belong to the owning
 * {@link LogBook} component.</p>
 */
final class LogBookState {
    private static final Logger LOG =
            LoggerFactory.getLogger(LogBookState.class);

    private final NodeId timingNodeId;
    private final LogBookData logBookData;
    private final TimingDataPersistence timingDataPersistence;
    private final TimingDataFactory timingDataFactory;
    private final TimeSource timeSource;
    private final MonotonicClock monotonicClock;
    private final LogBookMetrics metrics;

    private State state = State.CLOSED;
    private LocationId locationId;
    private boolean timingDataTailRepaired;
    private Throwable timingDataCommitFailure;
    private List<Problem> problems = Collections.emptyList();

    /** Creates the passive mutable state owned by one LogBook SerialLane. */
    LogBookState(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            LogBookMetrics metrics) {
        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        checkArgument(
                timingDataPersistence != null,
                "timingDataPersistence must not be null");
        checkArgument(
                timingDataFactory != null,
                "timingDataFactory must not be null");
        checkArgument(timeSource != null, "timeSource must not be null");
        checkArgument(monotonicClock != null, "monotonicClock must not be null");
        checkArgument(metrics != null, "metrics must not be null");

        this.timingNodeId = timingNodeId;
        this.timingDataPersistence = timingDataPersistence;
        this.timingDataFactory = timingDataFactory;
        this.timeSource = timeSource;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        this.logBookData = new LogBookData(timingNodeId);
    }

    /** Returns the immutable identity of the owning TimingNode. */
    NodeId timingNodeId() {
        return timingNodeId;
    }

    /** Returns the safely published committed TimingData source head. */
    long timingDataHead() {
        return logBookData.headSequence();
    }


    /** Opens the node at the requested location and commits the lifecycle record first. */
    OpenResult open(LocationId newLocationId)
            throws TimingDataPersistence.PersistenceException {
        checkArgument(newLocationId != null, "locationId must not be null");
        ensureOperational();
        if (state == State.OPEN) {
            return OpenResult.ALREADY_OPEN;
        }
        ensureTimingDataCommitAvailable();

        TimingTimestamp transitionTime =
                currentLifecycleTime();
        TimingData data =
                timingDataFactory.createNodeOpen(
                        nextLifecycleContext(
                                newLocationId,
                                transitionTime));
        commitTimingData(data);

        locationId = newLocationId;
        state = State.OPEN;
        return OpenResult.OPENED;
    }

    /** Closes the node and durably commits its lifecycle record before changing state. */
    CloseResult close()
            throws TimingDataPersistence.PersistenceException {
        ensureOperational();
        if (state == State.CLOSED) {
            return CloseResult.ALREADY_CLOSED;
        }
        ensureTimingDataCommitAvailable();

        LocationId closingLocation = locationId;
        TimingTimestamp transitionTime =
                currentLifecycleTime();
        TimingData data =
                timingDataFactory.createNodeClose(
                        nextLifecycleContext(
                                closingLocation,
                                transitionTime));
        commitTimingData(data);

        state = State.CLOSED;
        return CloseResult.CLOSED;
    }

    /** The antenna/TagProcessor path retains its observation timestamp. */
    RegistrationResult addAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp time)
            throws TimingDataPersistence.PersistenceException {
        return commitAutomaticRegistration(
                registrationId, time,
                TimingData.TagSource.ANT,
                TimingData.AutomaticTimeSource.OBS);
    }

    /** IF-03 direct simulation with explicit API-supplied effective time. */
    RegistrationResult simulateAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp time)
            throws TimingDataPersistence.PersistenceException {
        return commitAutomaticRegistration(
                registrationId, time,
                TimingData.TagSource.API,
                TimingData.AutomaticTimeSource.API);
    }

    /** IF-03 direct simulation captures time on the owning TimingNode lane. */
    RegistrationResult simulateAutomaticRegistrationNow(
            RegistrationId registrationId)
            throws TimingDataPersistence.PersistenceException {
        return commitAutomaticRegistration(
                registrationId, atCentisecond(timeSource.now()),
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
        ensureOperational();
        if (state != State.OPEN) {
            return RegistrationResult.nodeNotOpen();
        }
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createAutomaticRegistration(
                nextRegistrationContext(
                        locationId,
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
        ensureOperational();
        if (state != State.OPEN) {
            return RegistrationResult.nodeNotOpen();
        }
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createManualRegistration(
                nextRegistrationContext(
                        normalizeRegistrationTime(effectiveTime)),
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
        ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data =
                timingDataFactory.createAutomaticRegistration(
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
        ensureOperational();
        ensureTimingDataCommitAvailable();

        TimingData data =
                timingDataFactory.createManualRegistration(
                        nextRegistrationContext(
                                originalLocationId,
                                originalTime),
                        registrationId,
                        originalTimeSource,
                        TimingData.RegistrationAction.REV);
        return commitRegistration(data);
    }

    /** Returns the committed TimingData count while the LogBook is operational. */
    int timingDataCount() {
        ensureOperational();
        return logBookData.size();
    }

    /**
     * Returns a bounded copy of committed TimingData in source-sequence order.
     */
    List<TimingData> timingDataRange(
            long fromSequence,
            int limit) {
        ensureOperational();
        List<TimingData> records = new ArrayList<TimingData>();
        logBookData.visitRange(fromSequence, limit, records::add);
        return Collections.unmodifiableList(records);
    }

    /** Visits a bounded committed range and returns the current total record count. */
    int visitTimingDataRange(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor) {
        ensureOperational();
        logBookData.visitRange(fromSequence, limit, visitor);
        return logBookData.size();
    }

    /** Visits the newest committed records and returns the current total record count. */
    int visitLatestTimingData(
            int limit,
            Consumer<TimingData> visitor) {
        ensureOperational();
        logBookData.visitLatest(limit, visitor);
        return logBookData.size();
    }

    /** Builds the current immutable node status from this passive mutable state. */
    Status status() {
        return new Status(
                timingNodeId,
                state,
                locationId,
                timingDataTailRepaired,
                problems);
    }

    /** Loads persisted TimingData into committed in-memory history during activation. */
    TimingDataPersistence.LoadResult loadPersistedTimingData()
            throws TimingDataPersistence.PersistenceException {
        TimingDataPersistence.LoadResult loadResult = timingDataPersistence.load();
        for (TimingData data : loadResult.records()) {
            logBookData.add(data);
        }
        timingDataTailRepaired = loadResult.repairedIncompleteTail();
        return loadResult;
    }

    /** Moves the node into ERROR state after persisted TimingData recovery fails. */
    void markTimingDataLoadFailed(Throwable failure) {
        checkArgument(failure != null, "failure must not be null");
        state = State.ERROR;
        locationId = null;
        timingDataTailRepaired = false;

        String detail = failure.getMessage();
        String message = "Persisted TimingData load failed for " + timingNodeId.value();
        if (detail != null && !detail.trim().isEmpty()) {
            message += ": " + detail.trim();
        }
        problems = Collections.singletonList(
                new Problem(
                        ProblemCode.TIMING_DATA_LOAD_FAILED,
                        ProblemSeverity.ERROR,
                        message));
    }

    /** Builds the next registration context using the node's current location. */
    private Context nextRegistrationContext(TimingTimestamp effectiveTime) {
        return nextRegistrationContext(
                locationId,
                effectiveTime);
    }

    /** Builds the next registration context with explicit location and recorded time. */
    private Context nextRegistrationContext(
            LocationId recordLocationId,
            TimingTimestamp effectiveTime) {
        return new Context(
                timingNodeId,
                logBookData.nextSequence(),
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
                timingNodeId,
                logBookData.nextSequence(),
                recordLocationId,
                effectiveTime,
                currentRecordedTime(),
                tagSource,
                timeProvenance);
    }

    /** Builds the next lifecycle-record context at the supplied effective time. */
    private Context nextLifecycleContext(
            LocationId lifecycleLocation,
            TimingTimestamp effectiveTime) {
        return new Context(
                timingNodeId,
                logBookData.nextSequence(),
                lifecycleLocation,
                effectiveTime,
                currentRecordedTime());
    }

    /**
     * Captures reference lifecycle time at centisecond resolution.
     *
     * <p>Precision reduction truncates instead of rounding so the represented
     * transition is never moved forward in time.</p>
     */
    private TimingTimestamp currentLifecycleTime() {
        return atCentisecond(
                timeSource.now());
    }

    /**
     * Normalizes an accepted registration effective time to the IF-05 v1
     * centisecond boundary. Truncation never moves the observation forward.
     */
    private static TimingTimestamp normalizeRegistrationTime(
            TimingTimestamp time) {
        checkArgument(time != null, "registration time must not be null");
        return atCentisecond(
                time.instant());
    }

    /** Truncates an Instant to the configured centisecond effective-time precision. */
    private static TimingTimestamp atCentisecond(
            Instant instant) {
        long nanos =
                (instant.getNano() / 10_000_000L)
                        * 10_000_000L;
        return new TimingTimestamp(
                Instant.ofEpochSecond(
                        instant.getEpochSecond(),
                        nanos));
    }

    /** Captures record-creation bookkeeping at millisecond resolution. */
    private TimingTimestamp currentRecordedTime() {
        return new TimingTimestamp(
                timeSource.now().truncatedTo(
                        ChronoUnit.MILLIS));
    }

    /** Returns the committed record count without repeating operational validation. */
    int committedTimingDataCount() {
        return logBookData.size();
    }

    /** Returns the newest committed TimingData record or fails when history is empty. */
    TimingData latestCommittedTimingData() {
        final TimingData[] latest = new TimingData[1];
        logBookData.visitLatest(
                1,
                data -> latest[0] = data);
        if (latest[0] == null) {
            throw new IllegalStateException(
                    "no committed TimingData is available");
        }
        return latest[0];
    }

    /** Rejects normal LogBook operations after this node entered ERROR state. */
    private void ensureOperational() {
        if (state != State.ERROR) {
            return;
        }
        String detail = problems.isEmpty()
                ? "TimingNode is in ERROR"
                : problems.get(0).message();
        throw new IllegalStateException(detail);
    }

    /** Blocks further commits after an earlier persistence or in-memory commit failure. */
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
        if (data == null) {
            throw new IllegalStateException("timingDataFactory returned null");
        }

        metrics.recordAppendAttempt();
        long appendStartedNanos = monotonicClock.nowNanos();
        try {
            timingDataPersistence.append(data);
        } catch (TimingDataPersistence.PersistenceException ex) {
            metrics.recordAppendDuration(
                    monotonicClock.nowNanos() - appendStartedNanos);
            metrics.recordAppendFailure();
            timingDataCommitFailure = ex;
            throw ex;
        }
        metrics.recordAppendDuration(
                monotonicClock.nowNanos() - appendStartedNanos);

        try {
            logBookData.add(data);
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
        String location = data.locationId() == null
                ? "-"
                : String.valueOf(data.locationId().value());

        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration registration =
                    (TimingData.AutomaticRegistration) data;
            LOG.info(
                    "TimingData committed: node={}, sequence={}, type={}, registrationId={}, action={}, "
                            + "location={}, effectiveTime={}, tagSource={}, timeSource={}",
                    timingNodeId.value(),
                    data.sequenceNumber(),
                    type,
                    registration.registrationId().value(),
                    registration.action(),
                    location,
                    data.effectiveTime(),
                    registration.tagSource(),
                    registration.registrationTimeSource());
            return;
        }

        if (data instanceof TimingData.ManualRegistration) {
            TimingData.ManualRegistration registration =
                    (TimingData.ManualRegistration) data;
            LOG.info(
                    "TimingData committed: node={}, sequence={}, type={}, registrationId={}, action={}, "
                            + "location={}, effectiveTime={}, timeSource={}",
                    timingNodeId.value(),
                    data.sequenceNumber(),
                    type,
                    registration.registrationId().value(),
                    registration.action(),
                    location,
                    data.effectiveTime(),
                    registration.timeSource());
            return;
        }

        LOG.info(
                "TimingData committed: node={}, sequence={}, type={}, location={}, effectiveTime={}",
                timingNodeId.value(),
                data.sequenceNumber(),
                type,
                location,
                data.effectiveTime());
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
