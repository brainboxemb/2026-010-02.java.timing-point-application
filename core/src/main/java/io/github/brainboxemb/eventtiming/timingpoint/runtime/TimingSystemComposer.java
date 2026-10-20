package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystemConductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node.NodeUpstream;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.Upstream;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.EngineerApiConnector;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfig;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Constructs concrete Domain and I/O components for one configured system.
 *
 * <p>Only the backing executor resources and application coordination are shared.
 * Nodes, their persistence, antenna set, Upstream protocol state and AntennaManager are
 * built independently for each system. Components create their own logical lanes
 * on Runtime-owned shared schedulers. Runtime passes the optional AntennaManager
 * directly to the Domain Conductor for non-blocking desired-inventory control.
 * Runtime builds the children; the resulting {@link TimingSystem} owns
 * system-level event wiring and lifecycle.
 * Node-internal child composition belongs to {@link TimingNodeComposer}. This
 * composer has no lifecycle.</p>
 */
final class TimingSystemComposer {
    private static final Duration ANTENNA_CONTROL_TIMEOUT = Duration.ofSeconds(2);
    private static final AntennaId DEFAULT_ANTENNA_ID = new AntennaId("1");

    private final RuntimeExecutors executors;
    private final TimingNodeComposer nodeComposer;

    /** Creates a stateless composer over shared runtime infrastructure. */
    TimingSystemComposer(
            ApplicationConfiguration configuration,
            RuntimeExecutors executors,
            PlatformEnvironment platform) {

        this.executors = executors;
        this.nodeComposer =
                new TimingNodeComposer(
                        configuration,
                        executors,
                        platform.monotonicClock());
    }

    /** Composes one complete TimingSystem from already resolved runtime inputs. */
    TimingSystem compose(
            TimingSystemResolvedData system,
            AntennaManagerConfig managerBinding,
            AntennaSet implicitAntennas,
            TimeSource timeSource,
            EngineerApiConnector upstreamConnector) {

        AntennaSet antennaSet = createAntennas(managerBinding, implicitAntennas);
        List<TimingNodeComposition> nodeCompositions =
                createNodeCompositions(
                        system,
                        antennaSet,
                        timeSource);
        TimingNodeList nodes =
                timingNodes(nodeCompositions);

        AntennaManager antennaManager = antennaSet.isEmpty()
                ? null : new AntennaManager(
                        antennaSet,
                        executors.createAntennaControlLane(ANTENNA_CONTROL_TIMEOUT));

        TimingSystemConductor timingSystemConductor = new TimingSystemConductor(
                nodes,
                antennaManager,
                executors.createTimingSystemConductorLane());

        Upstream upstream =
                createUpstream(
                        nodeCompositions,
                        upstreamConnector);

        return new TimingSystem(
                system.configuration().timingSystemId(),
                nodes,
                antennaManager,
                timingSystemConductor,
                upstream);
    }

    /** Composes all TimingNodes and retains only the seams needed for system upstream. */
    private List<TimingNodeComposition> createNodeCompositions(
            TimingSystemResolvedData system,
            AntennaSet antennas,
            TimeSource timeSource) {

        List<TimingNodeComposition> nodes =
                new ArrayList<TimingNodeComposition>();
        for (Config.TimingNodeConfig nodeConfig
                : system.configuration().timingNodes()) {
            AntennaId antennaId =
                    resolveAntennaId(
                            nodeConfig,
                            antennas);
            nodes.add(
                    nodeComposer.compose(
                            nodeConfig,
                            antennaId,
                            system,
                            timeSource));
        }
        return nodes;
    }

    private static TimingNodeList timingNodes(
            List<TimingNodeComposition> compositions) {

        List<TimingNode> nodes =
                new ArrayList<TimingNode>();
        for (TimingNodeComposition composition
                : compositions) {
            nodes.add(composition.node());
        }
        return new TimingNodeList(nodes);
    }

    /** Builds one independent protocol-state instance for every TimingNode. */
    private Upstream createUpstream(
            List<TimingNodeComposition> compositions,
            EngineerApiConnector upstreamConnector) {

        List<NodeUpstream> nodeUpstreams =
                new ArrayList<NodeUpstream>();
        for (TimingNodeComposition composition
                : compositions) {
            TimingNode node = composition.node();
            NodeUpstream nodeUpstream =
                    new NodeUpstream(
                            node.nodeId(),
                            executors.timingNodeScheduler(),
                            UpstreamStream.forNode(
                                    upstreamConnector,
                                    node.nodeId()),
                            composition.logBook(),
                            composition.nodeService());

            upstreamConnector.bindNode(
                    node.nodeId(),
                    nodeUpstream::inputAvailable,
                    () -> {
                        nodeUpstream.sendTimingDataSync();
                        nodeUpstream.heartbeatTick();
                    });
            nodeUpstreams.add(nodeUpstream);
        }
        return new Upstream(
                upstreamConnector,
                nodeUpstreams);
    }

    /** Resolves an optional node antenna id against the antennas available to this system. */
    private static AntennaId resolveAntennaId(
            Config.TimingNodeConfig nodeConfig,
            AntennaSet antennas) {

        AntennaId configured = nodeConfig.antennaId();
        List<AntennaId> available = antennas.antennaIds();

        if (configured != null) {
            if (!available.isEmpty() && !available.contains(configured)) {
                throw new IllegalArgumentException(
                        "TimingNode " + nodeConfig.timingNodeId().value()
                                + " references unknown AntennaId " + configured.value());
            }
            return configured;
        }

        if (available.size() == 1) {
            return available.get(0);
        }

        if (available.isEmpty()) {
            return DEFAULT_ANTENNA_ID;
        }

        throw new IllegalArgumentException(
                "TimingNode " + nodeConfig.timingNodeId().value()
                        + " requires antennaId because multiple antennas are configured");
    }

    /** Creates the system antenna set from explicit binding or implicit composition input. */
    private static AntennaSet createAntennas(
            AntennaManagerConfig binding,
            AntennaSet implicitAntennas) {

        if (binding == null) {
            return implicitAntennas;
        }

        AntennaSet antennas = new AntennaSet();
        for (AntennaManagerConfig.AntennaConfig antenna : binding.antennas()) {
            if (!"simulated".equals(antenna.providerId())) {
                throw new IllegalArgumentException(
                        "Unknown AntennaProvider id " + antenna.providerId());
            }
            antennas.add(antenna.id(), new SimulatedAntenna());
        }
        if (!binding.inventoryGroup().isEmpty()) {
            antennas.inventoryGroup(
                    binding.inventoryInterval(),
                    binding.inventoryGroup().toArray(new AntennaId[0]));
        }
        return antennas;
    }
}
