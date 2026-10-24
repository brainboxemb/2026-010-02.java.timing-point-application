package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Applies one new RACE_DATA message through NodeService before confirming its DN_PACKET.
 */
final class RaceDataTask implements YieldingTask {
    private final BooleanSupplier available;
    private final DownstreamDelivery delivery;
    private final NodeService nodeService;
    private final long packetId;
    private final UpstreamData.RaceData raceData;

    private CompletableFuture<Void> applyFuture;

    RaceDataTask(
            BooleanSupplier available,
            DownstreamDelivery delivery,
            NodeService nodeService,
            long packetId,
            UpstreamData.RaceData raceData) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(delivery != null, "delivery must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(packetId > 0L, "packetId must be positive");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        this.available = available;
        this.delivery = delivery;
        this.nodeService = nodeService;
        this.packetId = packetId;
        this.raceData = raceData;
    }

    @Override
    public TaskStep runStep() {
        if (applyFuture == null) {
            if (!available.getAsBoolean()) {
                delivery.cancelProcessing();
                return TaskStep.done();
            }

            applyFuture = nodeService.applyRaceData(raceData);
            if (applyFuture == null) {
                delivery.cancelProcessing();
                throw new IllegalStateException(
                        "NodeService returned no RACE_DATA completion");
            }
            return TaskStep.await(applyFuture);
        }

        applyFuture.join();
        delivery.completeProcessing(packetId);
        return TaskStep.done();
    }
}
