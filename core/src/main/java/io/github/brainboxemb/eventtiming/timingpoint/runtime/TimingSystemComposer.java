package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystem;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.TimingSystemConductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSettings;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfig;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructs concrete Domain and I/O components for one configured system.
 *
 * <p>Only the backing executor resources and application coordination are shared.
 * Nodes, their persistence, logical lanes, antenna set and AntennaManager are
 * built independently for each system. Runtime passes the shared
 * AntennaSettings to the Domain Conductor, not AntennaManager itself.
 * Runtime builds the children and a pure antenna-to-node routing plan. The
 * resulting {@link TimingSystem} owns system-level event wiring and lifecycle.
 * Node-internal child composition belongs to {@link TimingNodeComposer}. This
 * composer has no lifecycle.</p>
 */
final class TimingSystemComposer {
    private static final Duration ANTENNA_CONTROL_TIMEOUT = Duration.ofSeconds(2);

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
            TimeSource timeSource) {
        TimingNodeList nodes = createNodes(system, timeSource);
        if (managerBinding == null && !implicitAntennas.isEmpty() && nodes.size() != 1) {
            throw new IllegalArgumentException(
                    "Implicit antenna routing requires exactly one TimingNode");
        }
        AntennaSet antennaSet = createAntennas(managerBinding, implicitAntennas);

        AntennaSettings antennaSettings = antennaSet.isEmpty() ? null : new AntennaSettings();
        AntennaManager antennaManager = antennaSet.isEmpty()
                ? null : new AntennaManager(
                        antennaSet,
                        executors.createAntennaControlLane(),
                        executors.sharedIoScheduledExecutor(),
                        ANTENNA_CONTROL_TIMEOUT,
                        antennaSettings);

        TimingSystemConductor timingSystemConductor = new TimingSystemConductor(
                nodes,
                antennaSettings,
                executors.createTimingSystemConductorLane());

        Map<AntennaId, TimingNodeList> antennaToNodes =
                mapAntennasToNodes(antennaSet, managerBinding, nodes);

        return new TimingSystem(
                system.configuration().timingSystemId(),
                nodes,
                antennaManager,
                timingSystemConductor,
                antennaToNodes);
    }

    /** Composes all TimingNodes configured for this resolved timing system. */
    private TimingNodeList createNodes(
            TimingSystemResolvedData system,
            TimeSource timeSource) {

        TimingNodeList.Builder nodes = TimingNodeList.builder();
        for (Config.TimingNodeConfig nodeConfig : system.configuration().timingNodes()) {
            nodes.add(nodeComposer.compose(nodeConfig, system, timeSource));
        }
        return nodes.build();
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

    /** Builds the antenna-to-node mapping consumed by the TimingSystem. */
    private static Map<AntennaId, TimingNodeList> mapAntennasToNodes(
            AntennaSet antennas,
            AntennaManagerConfig binding,
            TimingNodeList nodes) {

        Map<AntennaId, TimingNodeList> routes = new LinkedHashMap<AntennaId, TimingNodeList>();
        if (antennas.isEmpty()) {
            return routes;
        }

        Map<NodeId, TimingNode> byId = new LinkedHashMap<NodeId, TimingNode>();
        for (TimingNode node : nodes) {
            byId.put(node.timingNodeId(), node);
        }

        if (binding == null) {
            // Compatibility for the one-system, one-node test/Windows setup.
            TimingNode node = nodes.get(0);
            for (AntennaId antennaId : antennas.antennaIds()) {
                routes.put(antennaId, TimingNodeList.of(node));
            }
            return routes;
        }

        for (AntennaManagerConfig.AntennaConfig antenna : binding.antennas()) {
            TimingNodeList.Builder targets = TimingNodeList.builder();
            for (NodeId target : antenna.timingNodes()) {
                TimingNode node = byId.get(target);
                if (node == null) {
                    throw new IllegalArgumentException(
                            "Antenna " + antenna.id()
                                    + " targets unknown TimingNode "
                                    + target.value());
                }
                targets.add(node);
            }
            routes.put(antenna.id(), targets.build());
        }
        return routes;
    }
}
