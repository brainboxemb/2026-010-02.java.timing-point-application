package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.application.ApplicationConductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.system.SystemConductor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeList;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Constructs concrete Domain and I/O components for one configured system.
 *
 * <p>Only the physical worker pools and application coordination are shared.
 * Nodes, their persistence, logical lanes, antenna set and AntennaManager are
 * built independently for each system. Runtime passes the shared
 * AntennaSettings to the Domain Conductor, not AntennaManager itself.
 * This class installs only system-level event routing before activation.
 * Node-internal child composition belongs to {@link TimingNodeComposer}.
 * It has no lifecycle.</p>
 */
final class TimingSystemComposer {
    private static final Duration ANTENNA_CONTROL_TIMEOUT = Duration.ofSeconds(2);

    private final RuntimeExecutors executors;
    private final ApplicationConductor applicationConductor;
    private final TimingNodeComposer nodeComposer;

    TimingSystemComposer(
            ApplicationConfiguration configuration,
            RuntimeExecutors executors,
            PlatformEnvironment platform,
            ApplicationConductor applicationConductor) {
        this.executors = executors;
        this.applicationConductor = applicationConductor;
        this.nodeComposer =
                new TimingNodeComposer(
                        configuration,
                        executors,
                        platform.monotonicClock());
    }

    TimingSystemComponents compose(
            TimingSystemResolvedData system,
            AntennaManagerConfig managerBinding,
            AntennaSet implicitAntennas,
            TimeSource timeSource) {
        List<TimingNodeComponents> nodeComponents =
                createNodes(
                        system,
                        timeSource);
        TimingNodeList nodes =
                timingNodes(
                        nodeComponents);
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
                        executors.sharedIoScheduler(),
                        ANTENNA_CONTROL_TIMEOUT,
                        antennaSettings);

        SystemConductor conductor = new SystemConductor(
                nodes,
                antennaSettings,
                executors.createSystemConductorLane());

        applicationConductor.registerTimingSystem(antennaManager, conductor);

        // Composition owns the event graph: each node signals the Conductor,
        // whose handler only wakes its task. The task later reads CURRENT
        // node status, so event payloads need not be buffered or replayed.
        for (TimingNode node : nodes) {
            node.logBook()
                    .statusChangedEvent()
                    .subscribe(
                            status ->
                                    conductor.onTimingNodeStatusChanged(
                                            node,
                                            status));
        }
        if (antennaManager != null) {
            routeObservations(
                    antennaManager,
                    antennaSet,
                    managerBinding,
                    nodeComponents);
        }

        return new TimingSystemComponents(
                system.configuration().timingSystemId(), nodes, antennaManager);
    }

    private List<TimingNodeComponents> createNodes(
            TimingSystemResolvedData system,
            TimeSource timeSource) {
        List<TimingNodeComponents> nodes =
                new ArrayList<TimingNodeComponents>();
        for (Config.TimingNodeConfig nodeConfig
                : system.configuration().timingNodes()) {
            nodes.add(
                    nodeComposer.compose(
                            nodeConfig,
                            system,
                            timeSource));
        }
        return nodes;
    }

    private static TimingNodeList timingNodes(
            List<TimingNodeComponents> components) {
        TimingNodeList nodes =
                new TimingNodeList();
        for (TimingNodeComponents component : components) {
            nodes.add(
                    component.node());
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
            List<TimingNodeComponents> nodes) {
        Map<NodeId, TimingNodeComponents> byId =
                new LinkedHashMap<NodeId, TimingNodeComponents>();
        for (TimingNodeComponents components : nodes) {
            byId.put(
                    components.node().timingNodeId(),
                    components);
        }

        if (binding == null) {
            // Compatibility for the one-system, one-node test/Windows setup.
            TagProcessor tags =
                    nodes.get(0).tagProcessor();
            for (AntennaId antennaId : antennas.antennaIds()) {
                manager.tagObservedEvent(antennaId)
                        .subscribe(
                                tags::onTagObserved);
            }
            return;
        }

        for (AntennaManagerConfig.AntennaConfig antenna : binding.antennas()) {
            for (NodeId target : antenna.timingNodes()) {
                TimingNodeComponents components =
                        byId.get(target);
                if (components == null) {
                    throw new IllegalArgumentException(
                            "Antenna " + antenna.id()
                                    + " targets unknown TimingNode "
                                    + target.value());
                }
                manager.tagObservedEvent(antenna.id())
                        .subscribe(
                                components.tagProcessor()::onTagObserved);
            }
        }
    }
}
