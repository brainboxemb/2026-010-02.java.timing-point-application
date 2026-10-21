package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.service.NodeService;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Applies one RACE_DATA message through NodeService before acknowledging it. */
final class RaceDataTask implements YieldingTask {
    private final NodeUpstream upstream;
    private final RaceDataSync sync;
    private final NodeService nodeService;
    private final UpstreamData.RaceData raceData;

    private CompletableFuture<Void> applyFuture;

    RaceDataTask(
            NodeUpstream upstream,
            RaceDataSync sync,
            NodeService nodeService,
            UpstreamData.RaceData raceData) {
        failIfInvalidArgument(upstream != null, "upstream must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        this.upstream = upstream;
        this.sync = sync;
        this.nodeService = nodeService;
        this.raceData = raceData;
    }

    @Override
    public TaskStep runStep() {
        if (applyFuture == null) {
            if (!upstream.isAvailable()
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
