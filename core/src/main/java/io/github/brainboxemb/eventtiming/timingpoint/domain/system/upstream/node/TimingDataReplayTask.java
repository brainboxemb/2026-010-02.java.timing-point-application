package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Serves one queued TIMING_DATA replay request from the retained LogBook.
 *
 * <p>Each batch read yields while the LogBook Data Lane completes. Replay state
 * stays in TimingDataSync; this task owns only the asynchronous continuation.
 * Later upstream messages remain queued in UpstreamStream until this replay is
 * completely finished.</p>
 */
final class TimingDataReplayTask implements YieldingTask {
    private final BooleanSupplier available;
    private final TimingDataSync sync;
    private final UpstreamData.Req request;

    private boolean started;
    private CompletableFuture<List<TimingRecord>> pendingBatch;

    TimingDataReplayTask(
            BooleanSupplier available,
            TimingDataSync sync,
            UpstreamData.Req request) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(request != null, "request must not be null");
        this.available = available;
        this.sync = sync;
        this.request = request;
    }

    @Override
    public TaskStep runStep() {
        if (!available.getAsBoolean()) {
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
