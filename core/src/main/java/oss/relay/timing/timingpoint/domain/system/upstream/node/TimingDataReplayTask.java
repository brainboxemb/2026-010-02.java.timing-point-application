package oss.relay.timing.timingpoint.domain.system.upstream.node;

import oss.relay.timing.timingdata.TimingRecord;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;
import oss.relay.timing.timingpoint.platform.execution.tasks.YieldingTask;
import oss.relay.timing.upstreamdata.UpstreamData;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

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
    private final Duration replayPacketDelay;

    private boolean started;
    private CompletableFuture<List<TimingRecord>> pendingBatch;

    TimingDataReplayTask(
            BooleanSupplier available,
            TimingDataSync sync,
            UpstreamData.TimingDataRequest request,
            Runnable requestAccepted,
            Runnable handoffCompleted) {
        this(
                available,
                sync,
                request,
                requestAccepted,
                handoffCompleted,
                Duration.ZERO);
    }

    TimingDataReplayTask(
            BooleanSupplier available,
            TimingDataSync sync,
            UpstreamData.TimingDataRequest request,
            Runnable requestAccepted,
            Runnable handoffCompleted,
            Duration replayPacketDelay) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(request != null, "request must not be null");
        failIfInvalidArgument(requestAccepted != null, "requestAccepted must not be null");
        failIfInvalidArgument(handoffCompleted != null, "handoffCompleted must not be null");
        failIfInvalidArgument(
                replayPacketDelay != null
                        && !replayPacketDelay.isNegative(),
                "replayPacketDelay must not be negative");
        this.available = available;
        this.sync = sync;
        this.request = request;
        this.requestAccepted = requestAccepted;
        this.handoffCompleted = handoffCompleted;
        this.replayPacketDelay = replayPacketDelay;
    }

    @Override
    public TaskStep runStep() {
        if (!available.getAsBoolean()) {
            sync.sessionUnavailable();
            return TaskStep.done();
        }

        if (!started) {
            sync.startReplay(
                    request,
                    replayPacketDelay);
            requestAccepted.run();
            started = true;
        }

        boolean sentBatch = false;
        if (pendingBatch != null) {
            List<TimingRecord> records = pendingBatch.join();
            pendingBatch = null;
            sync.sendNextBatch(records);
            sentBatch = true;
        }

        if (!sync.replayPending()) {
            if (sync.liveReady()) {
                handoffCompleted.run();
            }
            return TaskStep.done();
        }

        if (sentBatch
                && !replayPacketDelay.isZero()) {
            return TaskStep.after(
                    replayPacketDelay);
        }

        pendingBatch = sync.submitNextBatch();
        return TaskStep.await(pendingBatch);
    }
}
