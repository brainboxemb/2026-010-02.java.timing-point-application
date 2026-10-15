package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManagerTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Java component for one TimingSystem architecture responsibility.
 *
 * <p>A TimingSystem contains one or more TimingNodes and one TimingSystemConductor and
 * uses associated I/O components such as AntennaManager. It installs the fixed
 * system-level event links between those components before activation and owns
 * their lifecycle relationship. Runtime supplies already constructed components
 * and resolved routing data.</p>
 *
 * <p>This component is also the natural owner for the system-level SystemStatus,
 * which combines semantic state from the contained TimingNodes with relevant
 * operational state from the I/O components used by this TimingSystem.</p>
 */
public final class TimingSystem {
    private final String id;
    private final TimingNodeList timingNodes;
    private final AntennaManager antennaManager;
    private final TimingSystemConductor timingSystemConductor;
    private final SystemStatus systemStatus;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

    /**
     * Creates one complete TimingSystem and fixes its internal event graph.
     *
     * @param antennaToNodes antenna-to-node mapping prepared by Runtime composition
     */
    public TimingSystem(
            String id,
            TimingNodeList timingNodes,
            AntennaManager antennaManager,
            TimingSystemConductor timingSystemConductor,
            Map<AntennaId, TimingNodeList> antennaToNodes) {

        checkArgument(id != null && !id.trim().isEmpty(), "TimingSystem id must not be blank");
        checkArgument(timingNodes != null && !timingNodes.isEmpty(), "timingNodes must not be empty");
        checkArgument(timingSystemConductor != null, "timingSystemConductor must not be null");
        checkArgument(antennaToNodes != null, "antennaToNodes must not be null");
        checkArgument(
                antennaManager != null || antennaToNodes.isEmpty(),
                "antennaToNodes require an AntennaManager");

        this.id = id.trim();
        this.timingNodes = timingNodes;
        this.antennaManager = antennaManager;
        this.timingSystemConductor = timingSystemConductor;
        this.systemStatus = new SystemStatus(this.id, timingNodeIds(timingNodes));

        wireNodeStatus();
        wireAntennaObservations(
                antennaManager == null
                        ? Collections.<AntennaId, TimingNodeList>emptyMap()
                        : antennaToNodes);

        if (antennaManager != null) {
            lifecycle.register(antennaManager, AntennaManager::activate, AntennaManager::deactivate);
        }
        lifecycle.register(timingSystemConductor, TimingSystemConductor::activate, TimingSystemConductor::deactivate);
    }

    /** Returns the configured TimingSystem identity. */
    public String id() {

        return id;
    }

    /** Returns the immutable ordered TimingNodes contained by this TimingSystem. */
    public TimingNodeList timingNodes() {

        return timingNodes;
    }

    /** Returns the complete current semantic status of this TimingSystem. */
    public SystemStatus.Snapshot systemStatus() {

        refreshAntennaStatus();
        return systemStatus.current();
    }

    /** Activates I/O before Domain coordination. */
    public void activate() {

        try {
            lifecycle.activateAll();
            systemStatus.markActive();
        } catch (RuntimeException | Error failure) {
            systemStatus.markFailed(failure);
            throw failure;
        }
    }

    /** Deactivates children in reverse activation order. */
    public void deactivate() {

        try {
            lifecycle.deactivateAll();
            systemStatus.markInactive();
        } catch (RuntimeException | Error failure) {
            systemStatus.markFailed(failure);
            throw failure;
        }
    }

    /** Installs node-status wake links owned by this system boundary. */
    private void wireNodeStatus() {

        for (TimingNode node : timingNodes) {
            Consumer<Status> onStatusChanged = status -> onTimingNodeStatusChanged(node, status);
            node.logBook().statusChangedEvent().subscribe(onStatusChanged);
        }
    }

    /** Updates SystemStatus and wakes system coordination for one authoritative node status. */
    private void onTimingNodeStatusChanged(TimingNode node, Status status) {

        systemStatus.onTimingNodeStatus(status);
        timingSystemConductor.onTimingNodeStatusChanged(node, status);
    }

    /** Refreshes semantic antenna status without exposing the concrete AntennaManager. */
    private void refreshAntennaStatus() {

        if (antennaManager == null) {
            systemStatus.clearAntennaManager();
            return;
        }

        List<SystemStatus.AntennaStatus> statuses = new ArrayList<SystemStatus.AntennaStatus>();
        for (AntennaManagerTypes.AntennaStatus status : antennaManager.statuses()) {
            Throwable failure = status.failure();
            statuses.add(
                    new SystemStatus.AntennaStatus(
                            status.antennaId().value(),
                            status.selfTestPassed(),
                            antennaOperation(status.operation()),
                            failure != null,
                            failure == null ? null : failure.getMessage()));
        }

        systemStatus.updateAntennaManager(
                antennaManagerState(antennaManager.state()),
                antennaManager.isReady(),
                statuses);
    }

    /** Maps I/O AntennaManager lifecycle to its Domain SystemStatus value. */
    private static SystemStatus.AntennaManagerState antennaManagerState(AntennaManagerTypes.State state) {

        switch (state) {
            case NEW:
                return SystemStatus.AntennaManagerState.NEW;
            case ACTIVE:
                return SystemStatus.AntennaManagerState.ACTIVE;
            case DEACTIVATING:
                return SystemStatus.AntennaManagerState.DEACTIVATING;
            case INACTIVE:
                return SystemStatus.AntennaManagerState.INACTIVE;
            case FAILED:
                return SystemStatus.AntennaManagerState.FAILED;
            default:
                throw new IllegalStateException("Unsupported AntennaManager state " + state);
        }
    }

    /** Maps I/O antenna operation to its Domain SystemStatus value. */
    private static SystemStatus.AntennaOperation antennaOperation(
            AntennaManagerTypes.AntennaOperation operation) {

        switch (operation) {
            case INACTIVE:
                return SystemStatus.AntennaOperation.INACTIVE;
            case PREPARING:
                return SystemStatus.AntennaOperation.PREPARING;
            case READY:
                return SystemStatus.AntennaOperation.READY;
            case INVENTORY:
                return SystemStatus.AntennaOperation.INVENTORY;
            default:
                throw new IllegalStateException("Unsupported antenna operation " + operation);
        }
    }

    /** Extracts immutable node identities used by the SystemStatus component. */
    private static List<NodeId> timingNodeIds(TimingNodeList timingNodes) {

        List<NodeId> ids = new ArrayList<NodeId>();
        for (TimingNode node : timingNodes) {
            ids.add(node.timingNodeId());
        }
        return Collections.unmodifiableList(ids);
    }

    /** Connects AntennaManager observations to mapped TimingNode boundaries. */
    private void wireAntennaObservations(Map<AntennaId, TimingNodeList> antennaToNodes) {

        for (Map.Entry<AntennaId, TimingNodeList> route : antennaToNodes.entrySet()) {
            AntennaId antennaId = route.getKey();
            TimingNodeList targets = route.getValue();

            checkArgument(antennaId != null, "observation route antennaId must not be null");
            checkArgument(targets != null && !targets.isEmpty(), "observation route targets must not be empty");

            for (TimingNode node : targets) {
                checkArgument(
                        containsNode(node),
                        "observation route target %s does not belong to TimingSystem %s",
                        node.timingNodeId().value(),
                        id);

                Consumer<TagObservation> onTagObserved = node::onTagObserved;
                antennaManager.tagObservedEvent(antennaId).subscribe(onTagObserved);
            }
        }
    }

    /** Reports whether the supplied node is one of this system's owned TimingNodes. */
    private boolean containsNode(TimingNode node) {

        return timingNodes.contains(node);
    }
}
