package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.Upstream;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Java component for one TimingSystem architecture responsibility.
 *
 * <p>A TimingSystem contains one or more TimingNodes and one TimingSystemConductor and
 * uses associated I/O components such as AntennaManager. It owns the fixed
 * antenna-to-node bindings, the system-level Upstream component and lifecycle
 * relationship between its components.
 * Runtime supplies already constructed components.</p>
 */
public final class TimingSystem extends LifecycleComponent {
    private final String id;
    private final TimingNodeList timingNodes;
    private final AntennaManager antennaManager;
    private final Upstream upstream;
    private final ComponentLifecycleManager lifecycle = new ComponentLifecycleManager();

    /** Backward-compatible test composition without a transport implementation. */
    public TimingSystem(
            String id,
            TimingNodeList timingNodes,
            AntennaManager antennaManager,
            TimingSystemConductor timingSystemConductor) {

        this(
                id,
                timingNodes,
                antennaManager,
                timingSystemConductor,
                Upstream.unavailable());
    }

    /** Creates one complete TimingSystem and fixes its internal event graph. */
    public TimingSystem(
            String id,
            TimingNodeList timingNodes,
            AntennaManager antennaManager,
            TimingSystemConductor timingSystemConductor,
            Upstream upstream) {

        failIfInvalidArgument(id != null && !id.trim().isEmpty(), "TimingSystem id must not be blank");
        failIfInvalidArgument(timingNodes != null && !timingNodes.isEmpty(), "timingNodes must not be empty");
        failIfInvalidArgument(timingSystemConductor != null, "timingSystemConductor must not be null");
        failIfInvalidArgument(upstream != null, "upstream must not be null");

        this.id = id.trim();
        this.timingNodes = timingNodes;
        this.antennaManager = antennaManager;
        this.upstream = upstream;

        if (antennaManager != null) {
            bindAntennaObservations();
            lifecycle.register(antennaManager);
        }
        lifecycle.register(timingSystemConductor);
        lifecycle.register(upstream);
    }

    /** Returns the configured TimingSystem identity. */
    public String id() {

        return id;
    }

    /** Returns the immutable ordered TimingNodes contained by this TimingSystem. */
    public TimingNodeList timingNodes() {

        return timingNodes;
    }

    /** Returns the system-owned upstream protocol component. */
    public Upstream upstream() {

        return upstream;
    }

    /** Returns the optional AntennaManager owned by this system. */
    public AntennaManager antennaManager() {

        return antennaManager;
    }

    /** Activates I/O before Domain coordination. */
    @Override
    protected void onActivate() {

        lifecycle.activateAll();
    }

    /** Deactivates children in reverse activation order. */
    @Override
    protected void onDeactivate() {

        lifecycle.deactivateAll();
    }

    /** Binds each TimingNode to its already resolved antenna. */
    private void bindAntennaObservations() {

        for (TimingNode node : timingNodes) {
            TimingNode.TAG_OBSERVATIONS.bind(
                    node,
                    antennaManager.tagObservedEvent(node.antennaId()));
        }
    }

}
