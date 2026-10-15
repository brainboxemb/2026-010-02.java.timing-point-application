package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Status;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.SystemConductor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.TagObservation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Top-level application component for one configured timing system.
 *
 * <p>The system owns the application-level relationship between its I/O-side
 * AntennaManager and Domain-side SystemConductor/TimingNodes. It installs those
 * fixed event links during construction and owns their activation order. Runtime
 * supplies already constructed children and routing data; it does not retain
 * responsibility for the system's internal graph.</p>
 *
 * <p>This component is also the natural owner for a future central SystemStatus,
 * because it can observe system coordination, TimingNode state and antenna state
 * without introducing I/O dependencies into Domain classes.</p>
 */
public final class TimingSystem {
    private final String id;
    private final TimingNodeList timingNodes;
    private final AntennaManager antennaManager;
    private final SystemConductor systemConductor;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

    /**
     * Creates one complete TimingSystem and fixes its internal event graph.
     *
     * @param observationRoutes antenna-to-node routing prepared by Runtime composition
     */
    public TimingSystem(
            String id,
            TimingNodeList timingNodes,
            AntennaManager antennaManager,
            SystemConductor systemConductor,
            Map<AntennaId, TimingNodeList> observationRoutes) {

        checkArgument(id != null && !id.trim().isEmpty(), "TimingSystem id must not be blank");
        checkArgument(timingNodes != null && !timingNodes.isEmpty(), "timingNodes must not be empty");
        checkArgument(systemConductor != null, "systemConductor must not be null");
        checkArgument(observationRoutes != null, "observationRoutes must not be null");
        checkArgument(
                antennaManager != null || observationRoutes.isEmpty(),
                "observationRoutes require an AntennaManager");

        this.id = id.trim();
        this.timingNodes = timingNodes.copy();
        this.antennaManager = antennaManager;
        this.systemConductor = systemConductor;

        wireNodeStatus();
        wireAntennaObservations(
                antennaManager == null
                        ? Collections.<AntennaId, TimingNodeList>emptyMap()
                        : observationRoutes);

        if (antennaManager != null) {
            lifecycle.register(antennaManager, AntennaManager::activate, AntennaManager::deactivate);
        }
        lifecycle.register(systemConductor, SystemConductor::activate, SystemConductor::deactivate);
    }

    /** Returns the configured TimingSystem identity. */
    public String id() {

        return id;
    }

    /** Returns an independent ordered list of the system's TimingNodes. */
    public TimingNodeList timingNodes() {

        return timingNodes.copy();
    }

    /** Returns the optional AntennaManager owned by this system. */
    public AntennaManager antennaManager() {

        return antennaManager;
    }

    /** Activates I/O before Domain coordination. */
    public void activate() {

        lifecycle.activateAll();
    }

    /** Deactivates children in reverse activation order. */
    public void deactivate() {

        lifecycle.deactivateAll();
    }

    /** Installs node-status wake links owned by this system boundary. */
    private void wireNodeStatus() {

        for (TimingNode node : timingNodes) {
            Consumer<Status> onStatusChanged =
                    status -> systemConductor.onTimingNodeStatusChanged(node, status);
            node.logBook().statusChangedEvent().subscribe(onStatusChanged);
        }
    }

    /** Installs antenna observation routes without exposing TimingNode internals. */
    private void wireAntennaObservations(Map<AntennaId, TimingNodeList> observationRoutes) {

        for (Map.Entry<AntennaId, TimingNodeList> route : observationRoutes.entrySet()) {
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

        for (TimingNode candidate : timingNodes) {
            if (candidate == node) {
                return true;
            }
        }
        return false;
    }
}
