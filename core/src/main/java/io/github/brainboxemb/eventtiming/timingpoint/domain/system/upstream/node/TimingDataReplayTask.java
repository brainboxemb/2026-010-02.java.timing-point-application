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
 * Serves one queued TIMING_DATA_REQ from the retained LogBook.
 *
 * <p>Accepting the request and confirming its DN_PACKET is separate from serving the
 * requested records. Each LogBook batch read may yield while the Data Lane completes.</p>
 */
final class TimingDataReplayTask implements YieldingTask {
    private final BooleanSupplier available;
    private final TimingDataSync sync;
    private final UpstreamData.TimingDataRequest request;
    private final Runnable requestAccepted;
    private final Runnable handoffCompleted;

    private boolean started;
    private CompletableFuture<List<TimingRecord>> pendingBatch;

    TimingDataReplayTask(
            BooleanSupplier available,
            TimingDataSync sync,
            UpstreamData.TimingDataRequest request,
            Runnable requestAccepted,
            Runnable handoffCompleted) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(request != null, "request must not be null");
        failIfInvalidArgument(requestAccepted != null, "requestAccepted must not be null");
        failIfInvalidArgument(handoffCompleted != null, "handoffCompleted must not be null");
        this.available = available;
        this.sync = sync;
        this.request = request;
        this.requestAccepted = requestAccepted;
        this.handoffCompleted = handoffCompleted;
    }

    @Override
    public TaskStep runStep() {
        if (!available.getAsBoolean()) {
            sync.sessionUnavailable();
            return TaskStep.done();
        }

        if (!started) {
            sync.startReplay(request);
            requestAccepted.run();
            started = true;
        }

        if (pendingBatch != null) {
            List<TimingRecord> records = pendingBatch.join();
            pendingBatch = null;
            sync.sendNextBatch(records);
        }

        if (!sync.replayPending()) {
            if (sync.liveReady()) {
                handoffCompleted.run();
            }
            return TaskStep.done();
        }

        pendingBatch = sync.submitNextBatch();
        return TaskStep.await(pendingBatch);
    }
}
