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
 * Accepts one TIMING_DATA_REQ packet and serves its retained LogBook replay.
 *
 * <p>RECV confirms successful processing of the request packet as soon as its replay
 * barrier has been established. Serving the captured LogBook range is a separate semantic
 * action and may continue over multiple yielded task steps.</p>
 */
final class TimingDataReplayTask implements YieldingTask {
    private final BooleanSupplier available;
    private final DownstreamDelivery delivery;
    private final TimingDataSync sync;
    private final UpstreamData packet;
    private final UpstreamData.TimingDataRequest request;
    private final Runnable handoffCompleted;

    private boolean started;
    private CompletableFuture<List<TimingRecord>> pendingBatch;

    TimingDataReplayTask(
            BooleanSupplier available,
            DownstreamDelivery delivery,
            TimingDataSync sync,
            UpstreamData packet,
            UpstreamData.TimingDataRequest request,
            Runnable handoffCompleted) {
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(delivery != null, "delivery must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(request != null, "request must not be null");
        failIfInvalidArgument(handoffCompleted != null, "handoffCompleted must not be null");
        this.available = available;
        this.delivery = delivery;
        this.sync = sync;
        this.packet = packet;
        this.request = request;
        this.handoffCompleted = handoffCompleted;
    }

    @Override
    public TaskStep runStep() {
        if (!available.getAsBoolean()) {
            sync.sessionUnavailable();
            delivery.cancelPending();
            return TaskStep.done();
        }

        if (!started) {
            if (!delivery.begin(packet)) {
                return TaskStep.done();
            }

            sync.startReplay(request);
            delivery.complete(packet);
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
