package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;


import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Java component for one TimingSystem architecture responsibility.
 *
 * <p>A TimingSystem contains one or more TimingNodes and one TimingSystemConductor and
 * uses associated I/O components such as AntennaManager. It owns the fixed
 * antenna-to-node bindings and lifecycle relationship between its components.
 * Runtime supplies already constructed components.</p>
 */
public final class TimingSystem {
    private final String id;
    private final TimingNodeList timingNodes;
    private final AntennaManager antennaManager;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

    /** Creates one complete TimingSystem and fixes its internal event graph. */
    public TimingSystem(
            String id,
            TimingNodeList timingNodes,
            AntennaManager antennaManager,
            TimingSystemConductor timingSystemConductor) {

        checkArgument(id != null && !id.trim().isEmpty(), "TimingSystem id must not be blank");
        checkArgument(timingNodes != null && !timingNodes.isEmpty(), "timingNodes must not be empty");
        checkArgument(timingSystemConductor != null, "timingSystemConductor must not be null");

        this.id = id.trim();
        this.timingNodes = timingNodes;
        this.antennaManager = antennaManager;

        if (antennaManager != null) {
            bindAntennaObservations();
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

    /** Binds each TimingNode to its already resolved antenna. */
    private void bindAntennaObservations() {

        for (TimingNode node : timingNodes) {
            node.bind(
                    TimingNode.TAG_OBSERVATIONS,
                    antennaManager.tagObservedEvent(node.antennaId()));
        }
    }

}
