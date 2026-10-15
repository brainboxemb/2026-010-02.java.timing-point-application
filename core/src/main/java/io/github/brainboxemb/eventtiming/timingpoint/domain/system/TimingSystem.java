package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import java.util.Map;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Java component for one TimingSystem architecture responsibility.
 *
 * <p>A TimingSystem contains one or more TimingNodes and one TimingSystemConductor and
 * uses associated I/O components such as AntennaManager. It owns system-level
 * routing such as antenna-to-node bindings and the lifecycle relationship between
 * its components. Runtime supplies already constructed components and resolved
 * routing data.</p>
 */
public final class TimingSystem {
    private final String id;
    private final TimingNodeList timingNodes;
    private final AntennaManager antennaManager;
    private final TimingSystemConductor timingSystemConductor;
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

        if (antennaManager != null) {
            bindAntennaObservations(antennaToNodes);
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

    /** Binds AntennaManager observation sources to mapped TimingNode inputs. */
    private void bindAntennaObservations(Map<AntennaId, TimingNodeList> antennaToNodes) {

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

                node.bind(
                        TimingNode.TAG_OBSERVATIONS,
                        antennaManager.tagObservedEvent(antennaId));
            }
        }
    }

    /** Reports whether the supplied node is one of this system's owned TimingNodes. */
    private boolean containsNode(TimingNode node) {

        return timingNodes.contains(node);
    }
}
