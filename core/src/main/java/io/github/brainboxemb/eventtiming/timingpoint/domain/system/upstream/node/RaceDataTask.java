package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Applies one RACE_DATA message before confirming its containing downstream packet.
 *
 * <p>Duplicate/gap detection belongs to DownstreamDelivery. This task only calls NodeService
 * for a new eligible packet and advances delivery state after that asynchronous apply completes.</p>
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
            UpstreamData packet,
            UpstreamData.RaceData raceData) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(delivery != null, "delivery must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        this.available = available;
        this.delivery = delivery;
        this.nodeService = nodeService;
        this.packet = packet;
        this.raceData = raceData;
    }

    @Override
    public TaskStep runStep() {
        if (applyFuture == null) {
            if (!available.getAsBoolean()
                    || !delivery.begin(packet)) {
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
        delivery.complete(packet.packetId());
        return TaskStep.done();
    }
}
