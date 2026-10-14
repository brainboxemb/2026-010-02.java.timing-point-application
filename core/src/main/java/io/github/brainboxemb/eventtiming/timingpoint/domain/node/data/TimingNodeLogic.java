package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.racedata.RaceDataState;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.UniqueTeamRegistrations;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
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
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal mutable logic of one TimingNode.
 *
 * <p>This object contains the node state and domain decisions. It has no queue,
 * timeout or event-delivery responsibility; those belong to the owning
 * {@link NodeDataProcessor} component.</p>
 */
final class TimingNodeLogic {
    private static final Logger LOG =
            LoggerFactory.getLogger(TimingNodeLogic.class);

    private final NodeId timingNodeId;
    private final LogBook logBook;
    private final TimingDataPersistence timingDataPersistence;
    private final TimingDataFactory timingDataFactory;
    private final TimeSource timeSource;
    private final MonotonicClock monotonicClock;
    private final TimingNodeMetrics metrics;
    private final RaceDataState raceData;
    private final UniqueTeamRegistrations uniqueTeamRegistrations =
            new UniqueTeamRegistrations();
    private final StageTiming stageTiming;

    private State state = State.CLOSED;
    private LocationId locationId;
    private boolean timingDataTailRecovered;
    private Throwable timingDataCommitFailure;
    private List<Problem> problems = Collections.emptyList();

    TimingNodeLogic(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                monotonicClock,
                new TimingNodeMetrics());
    }

    TimingNodeLogic(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            MonotonicClock monotonicClock,
            TimingNodeMetrics metrics) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                EventData.empty(),
                monotonicClock,
                metrics);
    }

    TimingNodeLogic(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            MonotonicClock monotonicClock,
            TimingNodeMetrics metrics) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }

        if (timingDataPersistence == null) {
            throw new IllegalArgumentException("timingDataPersistence must not be null");
        }
        if (timingDataFactory == null) {
            throw new IllegalArgumentException("timingDataFactory must not be null");
        }
        if (timeSource == null) {
            throw new IllegalArgumentException("timeSource must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException("eventData must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException("monotonicClock must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }

        this.timingNodeId = timingNodeId;
        this.timingDataPersistence = timingDataPersistence;
        this.timingDataFactory = timingDataFactory;
        this.timeSource = timeSource;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        this.logBook = new LogBook(timingNodeId);
        this.raceData = new RaceDataState(eventData);
        this.stageTiming =
                new StageTiming(
                        eventData,
                        raceData.teamAssignments(),
                        raceData.stageStartTimes());
    }

    NodeId timingNodeId() {
        return timingNodeId;
    }

    TimingNodeMetrics metrics() {
        return metrics;
    }

    LogBook logBook() {
        return logBook;
    }

    long timingDataHead() {
        return logBook.headSequence();
    }


    /** Commits validated RaceData changes; caller must own the Data Lane. */
    void applyRaceData(UpstreamData.RaceData message) {
        raceData.applyChanges(message);
    }

    TeamId teamIdFor(RegistrationId registrationId) {
        return raceData.teamIdFor(registrationId);
    }

    TimingTimestamp stageStartTimeFor(TeamId teamId) {
        return raceData.stageStartTimeFor(teamId);
    }

    int uniqueTeamRegistrationCount() {
        return uniqueTeamRegistrations.uniqueTeamCount(raceData::teamIdFor);
    }

    List<StageTime> stageTiming(
            ZoneId raceTimeZone) {
        ensureOperational();
        if (raceTimeZone == null) {
            throw new IllegalArgumentException(
                    "raceTimeZone must not be null");
        }

        /*
         * StageTiming is a read-side view of the committed history. Keep the
         * LogBook passive: capture the current records on the owning node lane
         * and let StageTiming derive the ranking from that snapshot.
         */
        List<TimingData> committed =
                new ArrayList<TimingData>(
                        logBook.size());
        if (logBook.size() > 0) {
            logBook.visitRange(
                    1L,
                    logBook.size(),
                    committed::add);
        }
        return stageTiming.ranking(
                committed,
                raceTimeZone);
    }

    OpenResult open(LocationId newLocationId)
            throws TimingDataPersistence.PersistenceException {
        if (newLocationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
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

    int timingDataCount() {
        ensureOperational();
        return logBook.size();
    }

    int visitTimingDataRange(
            long fromSequence,
            int limit,
            Consumer<TimingData> visitor) {
        ensureOperational();
        logBook.visitRange(fromSequence, limit, visitor);
        return logBook.size();
    }

    int visitLatestTimingData(
            int limit,
            Consumer<TimingData> visitor) {
        ensureOperational();
        logBook.visitLatest(limit, visitor);
        return logBook.size();
    }

    Status status() {
        return new Status(
                timingNodeId,
                state,
                locationId,
                timingDataTailRecovered,
                problems);
    }

    TimingDataPersistence.LoadResult recoverTimingData()
            throws TimingDataPersistence.PersistenceException {
        TimingDataPersistence.LoadResult loadResult = timingDataPersistence.load();
        for (TimingData data : loadResult.records()) {
            logBook.add(data);
            updateUniqueTeamRegistrations(data);
        }
        timingDataTailRecovered = loadResult.repairedIncompleteTail();
        return loadResult;
    }

    void markTimingDataRecoveryFailed(Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        state = State.ERROR;
        locationId = null;
        timingDataTailRecovered = false;

        String detail = failure.getMessage();
        String message = "TimingData recovery failed for " + timingNodeId.value();
        if (detail != null && !detail.trim().isEmpty()) {
            message += ": " + detail.trim();
        }
        problems = Collections.singletonList(
                new Problem(
                        ProblemCode.TIMING_DATA_RECOVERY_FAILED,
                        ProblemSeverity.ERROR,
                        message));
    }

    private Context nextRegistrationContext(TimingTimestamp effectiveTime) {
        return nextRegistrationContext(
                locationId,
                effectiveTime);
    }

    private Context nextRegistrationContext(
            LocationId recordLocationId,
            TimingTimestamp effectiveTime) {
        return new Context(
                timingNodeId,
                logBook.nextSequence(),
                recordLocationId,
                effectiveTime,
                currentRecordedTime());
    }

    private Context nextRegistrationContext(
            LocationId recordLocationId,
            TimingTimestamp effectiveTime,
            TimingData.TagSource tagSource,
            TimingData.AutomaticTimeSource timeProvenance) {
        return new Context(
                timingNodeId,
                logBook.nextSequence(),
                recordLocationId,
                effectiveTime,
                currentRecordedTime(),
                tagSource,
                timeProvenance);
    }

    private Context nextLifecycleContext(
            LocationId lifecycleLocation,
            TimingTimestamp effectiveTime) {
        return new Context(
                timingNodeId,
                logBook.nextSequence(),
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
        if (time == null) {
            throw new IllegalArgumentException(
                    "registration time must not be null");
        }
        return atCentisecond(
                time.instant());
    }

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

    int committedTimingDataCount() {
        return logBook.size();
    }

    TimingData latestCommittedTimingData() {
        final TimingData[] latest = new TimingData[1];
        logBook.visitLatest(
                1,
                data -> latest[0] = data);
        if (latest[0] == null) {
            throw new IllegalStateException(
                    "no committed TimingData is available");
        }
        return latest[0];
    }

    private void ensureOperational() {
        if (state != State.ERROR) {
            return;
        }
        String detail = problems.isEmpty()
                ? "TimingNode is in ERROR"
                : problems.get(0).message();
        throw new IllegalStateException(detail);
    }

    private void ensureTimingDataCommitAvailable() {
        if (timingDataCommitFailure != null) {
            throw new IllegalStateException(
                    "TimingData commit is blocked after an earlier persistence failure",
                    timingDataCommitFailure);
        }
    }

    private RegistrationResult commitRegistration(TimingData data)
            throws TimingDataPersistence.PersistenceException {
        commitTimingData(data);
        return RegistrationResult.committed(data);
    }

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
            logBook.add(data);
            updateUniqueTeamRegistrations(data);
        } catch (RuntimeException ex) {
            timingDataCommitFailure = ex;
            throw ex;
        }

        metrics.recordCommit();
        logCommittedTimingData(data);
    }

    /**
     * Mirrors committed registration ADD/REV semantics into the compact
     * registration data used for current unique-team counts.
     *
     * <p>No TeamId is resolved here. Team resolution belongs to the current
     * EventData/TeamAssignments view and may change after the registration was
     * committed.</p>
     */
    private void updateUniqueTeamRegistrations(
            TimingData data) {
        if (data instanceof TimingData.AutomaticRegistration) {
            TimingData.AutomaticRegistration registration =
                    (TimingData.AutomaticRegistration) data;
            updateUniqueTeamRegistrations(
                    registration.registrationId(),
                    registration.action());
            return;
        }

        if (data instanceof TimingData.ManualRegistration) {
            TimingData.ManualRegistration registration =
                    (TimingData.ManualRegistration) data;
            updateUniqueTeamRegistrations(
                    registration.registrationId(),
                    registration.action());
        }
    }

    private void updateUniqueTeamRegistrations(
            RegistrationId registrationId,
            TimingData.RegistrationAction action) {
        if (action == TimingData.RegistrationAction.ADD) {
            uniqueTeamRegistrations.registered(
                    registrationId);
        } else if (action == TimingData.RegistrationAction.REV) {
            uniqueTeamRegistrations.revoked(
                    registrationId);
        }
    }

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
