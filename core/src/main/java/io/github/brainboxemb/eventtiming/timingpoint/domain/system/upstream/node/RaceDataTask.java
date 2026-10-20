package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletionStage;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Applies one RACE_DATA message and waits for NodeService before acknowledging it.
 */
final class RaceDataTask implements YieldingTask {
    private final UpstreamStream stream;
    private final UpstreamDataSync sync;
    private final UpstreamData.RaceData raceData;

    private CompletionStage<Void> applyCompletion;

    RaceDataTask(
            UpstreamStream stream,
            UpstreamDataSync sync,
            UpstreamData.RaceData raceData) {
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(raceData != null, "raceData must not be null");
        this.stream = stream;
        this.sync = sync;
        this.raceData = raceData;
    }

    @Override
    public TaskStep runStep() {
        if (!stream.isAvailable()) {
            sync.cancelPending();
            return TaskStep.done();
        }

        if (applyCompletion == null) {
            applyCompletion = sync.handleRaceData(raceData);
            return applyCompletion == null
                    ? TaskStep.done()
                    : TaskStep.await(applyCompletion);
        }

        applyCompletion.toCompletableFuture().join();
        if (!stream.isAvailable()) {
            sync.cancelPending();
            return TaskStep.done();
        }

        sync.completeRaceData(raceData.sequence());
        return TaskStep.done();
    }
}
