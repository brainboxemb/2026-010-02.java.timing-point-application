package oss.relay.timing.timingpoint.domain.system.upstream.node;

import oss.relay.timing.timingpoint.domain.node.service.NodeService;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;
import oss.relay.timing.timingpoint.platform.execution.tasks.YieldingTask;
import oss.relay.timing.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Applies one new RACE_DATA message through NodeService before confirming its DN_PACKET.
 */
final class RaceDataTask implements YieldingTask {
    private final BooleanSupplier available;
    private final DownstreamDelivery delivery;
    private final NodeService nodeService;
    private final UpstreamData packet;
    private final UpstreamData.RaceData raceData;

    private CompletableFuture<Void> applyFuture;

    RaceDataTask(
            BooleanSupplier available,
            DownstreamDelivery delivery,
            NodeService nodeService,
            UpstreamData packet) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(delivery != null, "delivery must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(
                packet.family() == UpstreamData.Family.RACE_DATA,
                "packet must carry RACE_DATA");
        this.available = available;
        this.delivery = delivery;
        this.nodeService = nodeService;
        this.packet = packet;
        this.raceData = (UpstreamData.RaceData) packet.message();
    }

    @Override
    public TaskStep runStep() {
        if (applyFuture == null) {
            if (!available.getAsBoolean()) {
                delivery.cancelPending();
                return TaskStep.done();
            }

            applyFuture = nodeService.applyRaceData(raceData);
            if (applyFuture == null) {
                delivery.cancelPending();
                throw new IllegalStateException(
                        "NodeService returned no RACE_DATA completion");
            }
            return TaskStep.await(applyFuture);
        }

        applyFuture.join();
        delivery.complete(packet);
        return TaskStep.done();
    }
}
