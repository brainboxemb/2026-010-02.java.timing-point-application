package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Problem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Passive mutable state behind one LogBook component.
 *
 * <p>This object owns only current node facts and committed in-memory TimingData
 * history. It has no TimingDataFactory, time source, persistence, metrics,
 * queueing, timeout, logging or event-delivery responsibility. State-changing
 * domain operations belong to {@link LogBookOperations}.</p>
 */
final class LogBookState {
    private final NodeId timingNodeId;
    private final LogBookData logBookData;

    private State state = State.CLOSED;
    private LocationId locationId;
    private boolean timingDataTailRepaired;
    private List<Problem> problems = Collections.emptyList();

    /** Creates empty passive LogBook state for one TimingNode. */
    LogBookState(NodeId timingNodeId) {

        checkArgument(timingNodeId != null, "timingNodeId must not be null");
        this.timingNodeId = timingNodeId;
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

    /** Returns the current lifecycle state. */
    State state() {

        return state;
    }

    /** Returns the current location, or null when no location is available. */
    LocationId locationId() {

        return locationId;
    }

    /** Marks a successful OPEN transition after its TimingData commit. */
    void markOpen(LocationId newLocationId) {

        checkArgument(newLocationId != null, "newLocationId must not be null");
        locationId = newLocationId;
        state = State.OPEN;
    }

    /** Marks a successful CLOSE transition after its TimingData commit. */
    void markClosed() {

        state = State.CLOSED;
    }

    /** Records whether startup recovery repaired an incomplete persisted tail. */
    void timingDataTailRepaired(boolean repaired) {

        timingDataTailRepaired = repaired;
    }

    /** Moves state to ERROR after startup recovery failed. */
    void markTimingDataLoadFailed(Problem problem) {

        checkArgument(problem != null, "problem must not be null");
        state = State.ERROR;
        locationId = null;
        timingDataTailRepaired = false;
        problems = Collections.singletonList(problem);
    }

    /** Rejects normal LogBook access after startup recovery entered ERROR state. */
    void ensureOperational() {

        if (state == State.ERROR) {
            String detail = problems.isEmpty() ? "TimingNode is in ERROR" : problems.get(0).message();
            throw new IllegalStateException(detail);
        }
    }

    /** Returns the next sequence implied by committed history without consuming it. */
    long nextTimingDataSequence() {

        return logBookData.nextSequence();
    }

    /** Adds one already durably committed TimingData value to in-memory history. */
    void addCommittedTimingData(TimingData data) {

        logBookData.add(data);
    }

    /** Returns the committed TimingData count while the LogBook is operational. */
    int timingDataCount() {

        ensureOperational();
        return logBookData.size();
    }

    /** Returns a bounded immutable copy of committed TimingData in source order. */
    List<TimingData> timingDataRange(long fromSequence, int limit) {

        ensureOperational();
        List<TimingData> records = new ArrayList<TimingData>();
        logBookData.visitRange(fromSequence, limit, records::add);
        return Collections.unmodifiableList(records);
    }

    /** Visits a bounded committed range and returns the current total record count. */
    int visitTimingDataRange(long fromSequence, int limit, Consumer<TimingData> visitor) {

        ensureOperational();
        logBookData.visitRange(fromSequence, limit, visitor);
        return logBookData.size();
    }

    /** Visits the newest committed records and returns the current total record count. */
    int visitLatestTimingData(int limit, Consumer<TimingData> visitor) {

        ensureOperational();
        logBookData.visitLatest(limit, visitor);
        return logBookData.size();
    }

    /** Builds the current immutable node status from passive state. */
    Status status() {

        return new Status(timingNodeId, state, locationId, timingDataTailRepaired, problems);
    }

    /** Returns the committed record count without repeating operational validation. */
    int committedTimingDataCount() {

        return logBookData.size();
    }

    /** Returns the newest committed TimingData record. */
    TimingData latestCommittedTimingData() {

        return logBookData.latest();
    }
}
