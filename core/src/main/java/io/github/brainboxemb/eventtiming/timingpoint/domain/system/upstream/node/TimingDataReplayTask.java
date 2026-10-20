package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Serves one queued TIMING_DATA replay request from the retained LogBook.
 *
 * <p>Each batch read yields while the LogBook Data Lane completes. Replay state
 * stays in TimingDataSync; this task owns only the asynchronous batch continuation.</p>
 */
final class TimingDataReplayTask implements YieldingTask {
    private final UpstreamStream stream;
    private final TimingDataSync sync;
    private final UpstreamData.Req request;

    private boolean started;
    private CompletableFuture<List<TimingRecord>> pendingBatch;

    TimingDataReplayTask(
            UpstreamStream stream,
            TimingDataSync sync,
            UpstreamData.Req request) {
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(request != null, "request must not be null");
        this.stream = stream;
        this.sync = sync;
        this.request = request;
    }

    @Override
    public TaskStep runStep() {
        if (!stream.isAvailable()) {
            sync.cancelReplay();
            return TaskStep.done();
        }

        if (!started) {
            sync.startReplay(request);
            started = true;
        }

        if (pendingBatch != null) {
            List<TimingRecord> records = pendingBatch.join();
            pendingBatch = null;
            sync.sendNextBatch(records);
        }

        if (!sync.replayPending()) {
            return TaskStep.done();
        }

        pendingBatch = sync.submitNextBatch();
        return TaskStep.await(pendingBatch);
    }
}
