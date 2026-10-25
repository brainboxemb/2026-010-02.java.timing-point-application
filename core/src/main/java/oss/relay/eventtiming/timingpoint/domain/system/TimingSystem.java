package oss.relay.eventtiming.timingpoint.domain.system;

import oss.relay.eventtiming.timingpoint.domain.node.TimingNode;
import oss.relay.eventtiming.timingpoint.domain.node.TimingNodeList;
import oss.relay.eventtiming.timingpoint.domain.system.upstream.Upstream;
import oss.relay.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstream;
import oss.relay.eventtiming.timingpoint.io.messaging.UpstreamStream;
import oss.relay.eventtiming.timingpoint.infra.lifecycle.ComponentLifecycleManager;
import oss.relay.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import oss.relay.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

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

        bindSystemEvents();

        if (antennaManager != null) {
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

    /**
     * Installs the fixed event graph between already constructed system components.
     *
     * <p>Runtime resolves construction inputs and mappings, but TimingSystem owns the
     * stable relationships between the components it contains.</p>
     */
    private void bindSystemEvents() {
        bindAntennaObservations();
        bindUpstream();
    }

    /** Binds each TimingNode boundary to its already resolved antenna source. */
    private void bindAntennaObservations() {
        if (antennaManager == null) {
            return;
        }

        for (TimingNode node : timingNodes) {
            antennaManager.tagObservedEvent(node.antennaId()).subscribe(node::onTagObserved);
        }
    }

    /** Binds each node-scoped upstream stream to the matching semantic endpoint. */
    private void bindUpstream() {
        for (TimingNode node : timingNodes) {
            NodeUpstream nodeUpstream = upstream.node(node.nodeId());
            UpstreamStream stream = upstream.stream(node.nodeId());

            failIfInvalidArgument((nodeUpstream == null) == (stream == null),
                    "Incomplete upstream binding for node %s", node.nodeId().value());
            if (nodeUpstream == null) {
                continue;
            }

            stream.CONNECTED.subscribe(ignored -> nodeUpstream.connectionAvailable());
            stream.DISCONNECTED.subscribe(ignored -> nodeUpstream.connectionUnavailable());
            stream.DATA_AVAILABLE.subscribe(ignored -> nodeUpstream.inputAvailable());

            // Post-commit TimingData is a wake signal; NodeUpstream rereads LogBook by sequence.
            node.NEW_DATA_ENTRY.subscribe(ignored -> nodeUpstream.timingDataChanged());
        }
    }

}
