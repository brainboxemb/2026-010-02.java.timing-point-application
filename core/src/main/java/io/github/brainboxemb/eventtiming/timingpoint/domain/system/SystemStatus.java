package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Current semantic status of one TimingSystem.
 *
 * <p>This Domain component aggregates immutable TimingNode status and semantic
 * system/I/O status values. It deliberately does not depend on concrete I/O
 * components such as AntennaManager.</p>
 */
public final class SystemStatus {

    /** Lifecycle state of the containing TimingSystem. */
    public enum State {
        NEW,
        ACTIVE,
        INACTIVE,
        FAILED
    }

    /** Semantic lifecycle state contributed by the system antenna capability. */
    public enum AntennaManagerState {
        NEW,
        ACTIVE,
        DEACTIVATING,
        INACTIVE,
        FAILED
    }

    /** Semantic operation state of one configured antenna. */
    public enum AntennaOperation {
        INACTIVE,
        PREPARING,
        READY,
        INVENTORY
    }

    /** Immutable semantic status of one configured antenna. */
    public static final class AntennaStatus {
        private final String antennaId;
        private final boolean selfTestPassed;
        private final AntennaOperation operation;
        private final boolean failed;
        private final String failureMessage;

        /** Creates one immutable semantic antenna status. */
        public AntennaStatus(
                String antennaId,
                boolean selfTestPassed,
                AntennaOperation operation,
                boolean failed,
                String failureMessage) {

            checkArgument(antennaId != null && !antennaId.trim().isEmpty(), "antennaId must not be blank");
            checkArgument(operation != null, "operation must not be null");
            this.antennaId = antennaId.trim();
            this.selfTestPassed = selfTestPassed;
            this.operation = operation;
            this.failed = failed;
            this.failureMessage = failureMessage == null ? null : failureMessage.trim();
        }

        /** Returns the configured antenna identity as a semantic status key. */
        public String antennaId() {

            return antennaId;
        }

        /** Reports whether startup self-test passed. */
        public boolean selfTestPassed() {

            return selfTestPassed;
        }

        /** Returns the current semantic antenna operation. */
        public AntennaOperation operation() {

            return operation;
        }

        /** Reports whether this antenna currently exposes a failure. */
        public boolean failed() {

            return failed;
        }

        /** Returns diagnostic failure text, or null when none is available. */
        public String failureMessage() {

            return failureMessage;
        }
    }

    /** Immutable point-in-time TimingSystem status snapshot. */
    public static final class Snapshot {
        private final String timingSystemId;
        private final State state;
        private final String failureMessage;
        private final int expectedTimingNodes;
        private final List<Status> timingNodeStatuses;
        private final boolean hasAntennaManager;
        private final AntennaManagerState antennaManagerState;
        private final boolean antennaReady;
        private final List<AntennaStatus> antennaStatuses;

        /** Creates one immutable snapshot from the component's current semantic state. */
        private Snapshot(
                String timingSystemId,
                State state,
                String failureMessage,
                int expectedTimingNodes,
                List<Status> timingNodeStatuses,
                boolean hasAntennaManager,
                AntennaManagerState antennaManagerState,
                boolean antennaReady,
                List<AntennaStatus> antennaStatuses) {

            this.timingSystemId = timingSystemId;
            this.state = state;
            this.failureMessage = failureMessage;
            this.expectedTimingNodes = expectedTimingNodes;
            this.timingNodeStatuses = Collections.unmodifiableList(new ArrayList<Status>(timingNodeStatuses));
            this.hasAntennaManager = hasAntennaManager;
            this.antennaManagerState = antennaManagerState;
            this.antennaReady = antennaReady;
            this.antennaStatuses = Collections.unmodifiableList(new ArrayList<AntennaStatus>(antennaStatuses));
        }

        /** Returns the internal TimingSystem identity represented by this snapshot. */
        public String timingSystemId() {

            return timingSystemId;
        }

        /** Returns the current TimingSystem lifecycle state. */
        public State state() {

            return state;
        }

        /** Returns TimingSystem failure text, or null when the system has no lifecycle failure. */
        public String failureMessage() {

            return failureMessage;
        }

        /** Reports whether every contained TimingNode has published a current Status. */
        public boolean timingNodeStatusComplete() {

            return timingNodeStatuses.size() == expectedTimingNodes;
        }

        /** Returns current published TimingNode statuses in configured node order. */
        public List<Status> timingNodeStatuses() {

            return timingNodeStatuses;
        }

        /** Reports whether this TimingSystem uses an AntennaManager. */
        public boolean hasAntennaManager() {

            return hasAntennaManager;
        }

        /** Returns semantic AntennaManager lifecycle state, or null when no manager is configured. */
        public AntennaManagerState antennaManagerState() {

            return antennaManagerState;
        }

        /** Reports whether configured antenna startup preparation has completed. */
        public boolean antennaReady() {

            return hasAntennaManager && antennaReady;
        }

        /** Returns immutable semantic status for configured antennas. */
        public List<AntennaStatus> antennaStatuses() {

            return antennaStatuses;
        }

        /** Returns semantic status for one configured antenna identity. */
        public AntennaStatus antenna(String antennaId) {

            checkArgument(antennaId != null && !antennaId.trim().isEmpty(), "antennaId must not be blank");
            for (AntennaStatus status : antennaStatuses) {
                if (status.antennaId().equals(antennaId.trim())) {
                    return status;
                }
            }
            throw new IllegalArgumentException("Unknown antenna " + antennaId);
        }
    }

    private final String timingSystemId;
    private final List<NodeId> timingNodeIds;
    private final Map<NodeId, Status> timingNodeStatuses = new LinkedHashMap<NodeId, Status>();

    private State state = State.NEW;
    private String failureMessage;
    private boolean hasAntennaManager;
    private AntennaManagerState antennaManagerState;
    private boolean antennaReady;
    private List<AntennaStatus> antennaStatuses = Collections.emptyList();

    /** Creates one SystemStatus component for the configured TimingNode identities. */
    SystemStatus(String timingSystemId, List<NodeId> timingNodeIds) {

        checkArgument(timingSystemId != null && !timingSystemId.trim().isEmpty(), "timingSystemId must not be blank");
        checkArgument(timingNodeIds != null && !timingNodeIds.isEmpty(), "timingNodeIds must not be empty");

        Set<NodeId> distinct = new LinkedHashSet<NodeId>();
        for (NodeId timingNodeId : timingNodeIds) {
            checkArgument(timingNodeId != null, "timingNodeId must not be null");
            checkArgument(distinct.add(timingNodeId), "duplicate TimingNode id %s", timingNodeId.value());
        }

        this.timingSystemId = timingSystemId.trim();
        this.timingNodeIds = Collections.unmodifiableList(new ArrayList<NodeId>(timingNodeIds));
    }

    /** Accepts one immutable TimingNode status published through its component boundary. */
    synchronized void onTimingNodeStatus(Status status) {

        checkArgument(status != null, "status must not be null");
        checkArgument(timingNodeIds.contains(status.timingNodeId()), "TimingNode %s does not belong to TimingSystem %s",
                status.timingNodeId().value(), timingSystemId);
        timingNodeStatuses.put(status.timingNodeId(), status);
    }

    /** Replaces the current semantic antenna capability contribution. */
    synchronized void updateAntennaManager(
            AntennaManagerState managerState,
            boolean ready,
            List<AntennaStatus> statuses) {

        checkArgument(managerState != null, "managerState must not be null");
        checkArgument(statuses != null, "statuses must not be null");
        for (AntennaStatus status : statuses) {
            checkArgument(status != null, "antenna status must not be null");
        }

        hasAntennaManager = true;
        antennaManagerState = managerState;
        antennaReady = ready;
        antennaStatuses = Collections.unmodifiableList(new ArrayList<AntennaStatus>(statuses));
    }

    /** Records that this TimingSystem has no antenna capability. */
    synchronized void clearAntennaManager() {

        hasAntennaManager = false;
        antennaManagerState = null;
        antennaReady = false;
        antennaStatuses = Collections.emptyList();
    }

    /** Marks the containing TimingSystem ACTIVE. */
    synchronized void markActive() {

        state = State.ACTIVE;
        failureMessage = null;
    }

    /** Marks the containing TimingSystem INACTIVE. */
    synchronized void markInactive() {

        state = State.INACTIVE;
    }

    /** Marks the containing TimingSystem FAILED while retaining current component detail. */
    synchronized void markFailed(Throwable failure) {

        checkArgument(failure != null, "failure must not be null");
        state = State.FAILED;
        String detail = failure.getMessage();
        failureMessage = detail == null || detail.trim().isEmpty()
                ? failure.getClass().getSimpleName()
                : detail.trim();
    }

    /** Returns one immutable current semantic TimingSystem status snapshot. */
    public synchronized Snapshot current() {

        List<Status> nodeStatuses = new ArrayList<Status>();
        for (NodeId timingNodeId : timingNodeIds) {
            Status status = timingNodeStatuses.get(timingNodeId);
            if (status != null) {
                nodeStatuses.add(status);
            }
        }

        return new Snapshot(
                timingSystemId,
                state,
                failureMessage,
                timingNodeIds.size(),
                nodeStatuses,
                hasAntennaManager,
                antennaManagerState,
                antennaReady,
                antennaStatuses);
    }
}
