package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Serves one queued TIMING_DATA replay request from the retained LogBook. */
final class TimingDataReplayTask implements YieldingTask {
    private final NodeUpstream upstream;
    private final TimingDataSync sync;
    private final UpstreamData.Req request;

    private boolean started;
    private CompletableFuture<List<TimingRecord>> pendingBatch;

    TimingDataReplayTask(
            NodeUpstream upstream,
            TimingDataSync sync,
            UpstreamData.Req request) {
        failIfInvalidArgument(upstream != null, "upstream must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(request != null, "request must not be null");
        this.upstream = upstream;
        this.sync = sync;
        this.request = request;
    }

    @Override
    public TaskStep runStep() {
        if (!upstream.isAvailable()) {
            sync.cancelReplay();
            return TaskStep.done();
        }

        if (!started) {
            sync.startReplay(request);
            started = true;
        }

        if (pendingBatch != null) {
            List<TimingRecord> records =
                    pendingBatch.join();
            pendingBatch = null;
            sync.sendNextBatch(records);
        }

        if (!sync.replayPending()) {
            return TaskStep.done();
        }

        pendingBatch =
                sync.submitNextBatch();
        return TaskStep.await(pendingBatch);
    }
}
