package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.SystemConductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaId;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaSet;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.model.SimulatedAntenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.PlatformEnvironment;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.AntennaManagerConfig;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constructs concrete Domain and I/O components for one configured system.
 *
 * <p>Only the physical worker pools and application coordination are shared.
 * Nodes, their persistence, logical lanes, antenna set and AntennaManager are
 * built independently for each system. Only the specific AntennaManager is
 * passed to the Domain Conductor; no I/O container crosses that boundary.
 * This class has no lifecycle.</p>
 */
final class TimingSystemComposer {
    private static final Duration ANTENNA_CONTROL_TIMEOUT = Duration.ofSeconds(2);

    private final ApplicationConfiguration configuration;
    private final RuntimeExecutors executors;
    private final PlatformEnvironment platform;
    private final ApplicationConductor applicationConductor;

    TimingSystemComposer(
            ApplicationConfiguration configuration,
            RuntimeExecutors executors,
            PlatformEnvironment platform,
            ApplicationConductor applicationConductor) {
        this.configuration = configuration;
        this.executors = executors;
        this.platform = platform;
        this.applicationConductor = applicationConductor;
    }

    TimingSystemComponents compose(
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

        AntennaManager antennaManager = antennaSet.isEmpty()
                ? null : new AntennaManager(
                        antennaSet,
                        executors.createAntennaControlExecutor(),
                        ANTENNA_CONTROL_TIMEOUT);

        SystemConductor conductor = new SystemConductor(
                nodes,
                antennaManager,
                executors.createSystemConductorExecutor());

        applicationConductor.registerTimingSystem(antennaManager, conductor);
        for (TimingNode node : nodes) {
            node.statusChangedEvent().subscribe(
                    ignored -> conductor.signalTimingNodeStateChanged(node));
        }
        if (antennaManager != null) {
            routeObservations(antennaManager, antennaSet, managerBinding, nodes);
        }

        return new TimingSystemComponents(
                system.configuration().timingSystemId(), nodes, antennaManager);
    }

    private TimingNodeList createNodes(
            TimingSystemResolvedData system,
            TimeSource timeSource) {
        TimingNodeList nodes = new TimingNodeList();
        for (Config.TimingNodeConfig nodeConfig : system.configuration().timingNodes()) {
            RuntimeExecutors.TimingNodeExecutors nodeExecutors =
                    executors.createTimingNodeExecutors();
            TimingDataPersistence persistence = new DefaultTimingDataPersistence(
                    new FileAppendOnlyRecordStore(nodeConfig.timingDataPath()),
                    nodeConfig.timingNodeId(),
                    system.timingDataCodec());
            TimingNode node = new TimingNode(
                    nodeConfig.timingNodeId(),
                    persistence,
                    system.timingDataFactory(),
                    timeSource,
                    configuration.timingNode(nodeConfig.timingNodeId()).tagProcessing(),
                    system.eventData(),
                    nodeExecutors.timingNode(),
                    nodeExecutors.tagProcessor(),
                    platform.monotonicClock());
            nodes.add(node);
        }
        return nodes;
    }

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

    private static void routeObservations(
            AntennaManager manager,
            AntennaSet antennas,
            AntennaManagerConfig binding,
            TimingNodeList nodes) {
        Map<NodeId, TimingNode> byId = new LinkedHashMap<NodeId, TimingNode>();
        for (TimingNode node : nodes) {
            byId.put(node.timingNodeId(), node);
        }

        if (binding == null) {
            // Compatibility for the one-system, one-node test/Windows setup.
            TimingNode onlyNode = nodes.get(0);
            for (AntennaId antennaId : antennas.antennaIds()) {
                manager.tagObservedEvent(antennaId)
                        .subscribe(onlyNode.tagProcessor()::onTagObserved);
            }
            return;
        }

        for (AntennaManagerConfig.AntennaConfig antenna : binding.antennas()) {
            for (NodeId target : antenna.timingNodes()) {
                TimingNode node = byId.get(target);
                if (node == null) {
                    throw new IllegalArgumentException(
                            "Antenna " + antenna.id() + " targets unknown TimingNode "
                                    + target.value());
                }
                manager.tagObservedEvent(antenna.id())
                        .subscribe(node.tagProcessor()::onTagObserved);
            }
        }
    }
}
