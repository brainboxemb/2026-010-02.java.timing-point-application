package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Catches live TIMING_DATA up from the authoritative LogBook after synchronization.
 *
 * <p>Commit events only wake this reusable task. Repeated wakes coalesce in AbstractTask while the
 * task reads bounded LogBook ranges from TimingDataSync's retained source cursor. If synchronization
 * is not complete, the task exits without sending; replay completion wakes it again so records
 * committed during replay are not lost or interleaved into the captured replay range.</p>
 */
final class TimingDataLiveTask extends AbstractTask {
    private static final Logger LOG = LoggerFactory.getLogger(TimingDataLiveTask.class);

    private final NodeId nodeId;
    private final BooleanSupplier available;
    private final TimingDataSync sync;

    private CompletableFuture<List<TimingRecord>> pendingBatch;

    TimingDataLiveTask(
            NodeId nodeId,
            BooleanSupplier available,
            TimingDataSync sync) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(sync != null, "sync must not be null");
        this.nodeId = nodeId;
        this.available = available;
        this.sync = sync;
    }

    @Override
    protected void resetForRun() {
        pendingBatch = null;
    }

    @Override
    public TaskStep runStep() {
        if (!available.getAsBoolean() || !sync.liveReady()) {
            return TaskStep.done();
        }

        if (pendingBatch != null) {
            List<TimingRecord> records = pendingBatch.join();
            pendingBatch = null;

            if (!available.getAsBoolean() || !sync.liveReady()) {
                return TaskStep.done();
            }
            sync.sendNextLiveBatch(records);
        }

        if (!sync.livePending()) {
            return TaskStep.done();
        }

        pendingBatch = sync.submitNextLiveBatch();
        return TaskStep.await(pendingBatch);
    }

    @Override
    protected void onRunCompleted(Throwable failure) {
        pendingBatch = null;
        if (failure != null) {
            LOG.warn(
                    "Live TIMING_DATA catch-up failed for node {}",
                    nodeId.value(),
                    failure);
        }
    }
}
