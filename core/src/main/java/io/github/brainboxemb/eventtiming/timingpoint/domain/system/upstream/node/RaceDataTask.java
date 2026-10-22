package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Applies one RACE_DATA message through NodeService before acknowledging it.
 *
 * <p>The task has exactly one asynchronous wait: NodeService.applyRaceData().
 * While that future is incomplete, SerialTaskRunner releases the shared worker.
 * UpstreamInputTask keeps this semantic task active and does not consume the next
 * queued upstream message until this task completes.</p>
 */
final class RaceDataTask implements YieldingTask {
    private final BooleanSupplier available;
    private final RaceDataSync sync;
    private final NodeService nodeService;
    private final UpstreamData.RaceData raceData;

    private CompletableFuture<Void> applyFuture;

    RaceDataTask(
            BooleanSupplier available,
            RaceDataSync sync,
            NodeService nodeService,
            UpstreamData.RaceData raceData) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        this.available = available;
        this.sync = sync;
        this.nodeService = nodeService;
        this.raceData = raceData;
    }

    @Override
    public TaskStep runStep() {
        if (applyFuture == null) {
            if (!available.getAsBoolean()
                    || !sync.beginApply(raceData)) {
                return TaskStep.done();
            }

            applyFuture =
                    nodeService.applyRaceData(raceData);
            if (applyFuture == null) {
                sync.cancelApply();
                throw new IllegalStateException(
                        "NodeService returned no RACE_DATA completion");
            }
            return TaskStep.await(applyFuture);
        }

        applyFuture.join();
        sync.completeApply(raceData.sequence());
        return TaskStep.done();
    }
}
