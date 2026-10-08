package io.github.brainboxemb.eventtiming.timingpoint.domain.system;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.AbstractConductor;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.CooperativeTaskController;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.TaskStep;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinates one TimingSystem.
 *
 * <p>The Conductor owns the lifecycle of the TimingNodes in this system and
 * reconciles their states into one inventory decision. Inventory is enabled
 * while at least one TimingNode is OPEN.</p>
 *
 * <p>The AntennaManager owns antenna power, self-test, initialization,
 * inventory execution and multiplexing. TagObservation routing is wired
 * separately by Runtime.</p>
 */
public final class Conductor extends AbstractConductor
        implements CooperativeTask {
    private static final Logger LOG =
            LoggerFactory.getLogger(Conductor.class);

    private final List<TimingNodeStateProperty> stateProperties =
            new ArrayList<TimingNodeStateProperty>();
    private final Map<TimingNode, TimingNodeStateProperty> propertiesByNode =
            new IdentityHashMap<TimingNode, TimingNodeStateProperty>();
    private final AntennaManager antennaManager;
    private final CooperativeTaskController taskController;

    public Conductor(
            List<TimingNode> timingNodes,
            AntennaManager antennaManager,
            SerialExecutor coordinationLane) {
        super(coordinationLane);

        if (timingNodes == null || timingNodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "timingNodes must not be empty");
        }

        this.antennaManager = antennaManager;
        this.taskController =
                new CooperativeTaskController(
                        new SerialTaskRunner(
                                coordinationLane()),
                        this,
                        this::onControlTaskFailure);

        for (TimingNode node : timingNodes) {
            if (node == null
                    || propertiesByNode.containsKey(node)) {
                throw new IllegalArgumentException(
                        "timingNodes must contain distinct non-null nodes");
            }

            TimingNodeStateProperty property =
                    new TimingNodeStateProperty(
                            node,
                            coordinationLane());
            stateProperties.add(property);
            propertiesByNode.put(
                    node,
                    property);

            property.changedEvent()
                    .subscribe(
                            ignored ->
                                    taskController.wake());

            registerComponent(
                    "TimingNode "
                            + node.timingNodeId().value(),
                    node::activate,
                    node::deactivate);
        }
    }

    public boolean signalTimingNodeStateChanged(
            TimingNode node) {
        TimingNodeStateProperty property =
                propertiesByNode.get(node);
        if (property == null) {
            throw new IllegalArgumentException(
                    "TimingNode does not belong to this TimingSystem");
        }
        return property.signalChanged();
    }

    TimingNodeStateProperty nodeStateProperty(
            TimingNode node) {
        TimingNodeStateProperty property =
                propertiesByNode.get(node);
        if (property == null) {
            throw new IllegalArgumentException(
                    "Unknown TimingNode");
        }
        return property;
    }

    @Override
    protected void onActivated() {
        for (TimingNodeStateProperty property
                : stateProperties) {
            property.initialize();
        }

        if (antennaManager != null) {
            taskController.wake();
        }
    }

    @Override
    public TaskStep runStep() {
        if (antennaManager == null) {
            return TaskStep.done();
        }

        boolean anyOpen = false;
        for (TimingNodeStateProperty property
                : stateProperties) {
            if (!property.initialized()) {
                return TaskStep.done();
            }
            if (property.currentValue()
                    == TimingNodeTypes.State.OPEN) {
                anyOpen = true;
            }
        }

        if (!antennaManager.setInventoryEnabled(
                anyOpen)) {
            LOG.warn(
                    "AntennaManager rejected inventory enabled={} managerState={}",
                    anyOpen,
                    antennaManager.state());
        }

        return TaskStep.done();
    }

    private void onControlTaskFailure(
            Throwable failure) {
        LOG.error(
                "TimingSystem Conductor control task failed",
                failure);
    }
}
