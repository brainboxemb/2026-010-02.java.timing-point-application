package io.github.brainboxemb.eventtiming.timingpoint.domain.timing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData.ManualTimeSource;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory.Context;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingpoint.domain.logbook.LogBook;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.CloseResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.OpenResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Problem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.ProblemCode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.ProblemSeverity;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.RegistrationResult;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Status;

import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Internal mutable logic of one TimingNode.
 *
 * <p>This object contains the node state and domain decisions. It has no queue,
 * timeout or event-delivery responsibility; those belong to the visible
 * {@link TimingNode} component boundary.</p>
 */
final class TimingNodeLogic {
    private final NodeId timingNodeId;
    private final LogBook logBook;
    private final TimingDataPersistence timingDataPersistence;
    private final TimingDataFactory timingDataFactory;
    private final TimeSource timeSource;

    private Lifecycle lifecycle = Lifecycle.CLOSED;
    private LocationId locationId;
    private boolean timingDataTailRecovered;
    private Throwable timingDataCommitFailure;
    private List<Problem> problems = Collections.emptyList();

    TimingNodeLogic(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
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

        this.timingNodeId = timingNodeId;
        this.timingDataPersistence = timingDataPersistence;
        this.timingDataFactory = timingDataFactory;
        this.timeSource = timeSource;
        this.logBook = new LogBook(timingNodeId);
    }

    NodeId timingNodeId() {
        return timingNodeId;
    }

    OpenResult open(LocationId newLocationId) {
        if (newLocationId == null) {
            throw new IllegalArgumentException("locationId must not be null");
        }
        ensureOperational();
        if (lifecycle == Lifecycle.OPEN) {
            return OpenResult.ALREADY_OPEN;
        }
        locationId = newLocationId;
        lifecycle = Lifecycle.OPEN;
        return OpenResult.OPENED;
    }

    CloseResult close() {
        ensureOperational();
        if (lifecycle == Lifecycle.CLOSED) {
            return CloseResult.ALREADY_CLOSED;
        }
        lifecycle = Lifecycle.CLOSED;
        return CloseResult.CLOSED;
    }

    RegistrationResult addAutomaticRegistration(
            RegistrationId registrationId,
            TimingTimestamp time)
            throws TimingDataPersistence.PersistenceException {
        ensureOperational();
        if (lifecycle != Lifecycle.OPEN) {
            return RegistrationResult.nodeNotOpen();
        }
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createAutomaticRegistration(
                nextRegistrationContext(time),
                registrationId);
        return commitRegistration(data);
    }

    RegistrationResult commitManualRegistration(
            RegistrationId registrationId,
            TimingTimestamp effectiveTime,
            ManualTimeSource registrationTimeSource)
            throws TimingDataPersistence.PersistenceException {
        ensureOperational();
        if (lifecycle != Lifecycle.OPEN) {
            return RegistrationResult.nodeNotOpen();
        }
        ensureTimingDataCommitAvailable();

        TimingData data = timingDataFactory.createManualRegistration(
                nextRegistrationContext(effectiveTime),
                registrationId,
                registrationTimeSource);
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
                lifecycle,
                locationId,
                timingDataTailRecovered,
                problems);
    }

    void recoverTimingData() throws TimingDataPersistence.PersistenceException {
        TimingDataPersistence.LoadResult loadResult = timingDataPersistence.load();
        for (TimingData data : loadResult.records()) {
            logBook.add(data);
        }
        timingDataTailRecovered = loadResult.repairedIncompleteTail();
    }

    void markTimingDataRecoveryFailed(Throwable failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        lifecycle = Lifecycle.ERROR;
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
        return new Context(
                timingNodeId,
                logBook.nextSequence(),
                locationId,
                effectiveTime,
                timeSource.now());
    }

    private void ensureOperational() {
        if (lifecycle != Lifecycle.ERROR) {
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
        if (data == null) {
            throw new IllegalStateException("timingDataFactory returned null");
        }

        try {
            timingDataPersistence.append(data);
        } catch (TimingDataPersistence.PersistenceException ex) {
            timingDataCommitFailure = ex;
            throw ex;
        }

        try {
            logBook.add(data);
        } catch (RuntimeException ex) {
            timingDataCommitFailure = ex;
            throw ex;
        }

        return RegistrationResult.committed(data);
    }
}
