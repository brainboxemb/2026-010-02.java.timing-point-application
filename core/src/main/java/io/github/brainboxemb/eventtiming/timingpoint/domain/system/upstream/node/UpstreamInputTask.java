package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns ordered stream-message admission and the active semantic task.
 *
 * <p>One task run drains queued input. The active semantic task's TaskStep is
 * returned directly to SerialTaskRunner, so AWAIT/AFTER release the shared
 * worker without a nested task run or a second completion future.</p>
 */
final class UpstreamInputTask extends AbstractTask {
    private static final Logger LOG = LoggerFactory.getLogger(UpstreamInputTask.class);

    private final NodeId nodeId;
    private final UpstreamStream stream;
    private final BooleanSupplier available;
    private final Supplier<UpstreamData> decoder;
    private final Function<UpstreamData, YieldingTask> taskFactory;
    private final Runnable failureCleanup;
    private YieldingTask currentMessageTask;

    UpstreamInputTask(
            NodeId nodeId,
            UpstreamStream stream,
            BooleanSupplier available,
            Supplier<UpstreamData> decoder,
            Function<UpstreamData, YieldingTask> taskFactory,
            Runnable failureCleanup) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(decoder != null, "decoder must not be null");
        failIfInvalidArgument(taskFactory != null, "taskFactory must not be null");
        failIfInvalidArgument(failureCleanup != null, "failureCleanup must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
        this.available = available;
        this.decoder = decoder;
        this.taskFactory = taskFactory;
        this.failureCleanup = failureCleanup;
    }

    @Override
    public TaskStep runStep() {
        if (currentMessageTask == null) {
            if (!available.getAsBoolean() || !stream.hasMessage()) {
                return TaskStep.done();
            }

            try {
                currentMessageTask = taskFactory.apply(decoder.get());
            } catch (RuntimeException ex) {
                reportMessageFailure(ex);
                return nextMessageStep();
            }
        }

        final TaskStep step;
        try {
            step = currentMessageTask.runStep();
        } catch (RuntimeException ex) {
            currentMessageTask = null;
            reportMessageFailure(ex);
            return nextMessageStep();
        }

        if (step == null) {
            currentMessageTask = null;
            reportMessageFailure(new IllegalStateException(
                    "Upstream semantic task returned no TaskStep"));
            return nextMessageStep();
        }

        if (step.type() != TaskStep.Type.DONE) {
            return step;
        }

        currentMessageTask = null;
        return nextMessageStep();
    }

    @Override
    protected void resetForRun() {
        currentMessageTask = null;
    }

    @Override
    protected void onRunCompleted(Throwable failure) {
        if (failure == null) {
            return;
        }

        if (currentMessageTask != null) {
            currentMessageTask = null;
            failureCleanup.run();
        }
        LOG.warn("Upstream input task failed for node {}", nodeId.value(), failure);
    }

    /** Yields before consuming a following queued message. */
    private TaskStep nextMessageStep() {
        return available.getAsBoolean() && stream.hasMessage()
                ? TaskStep.again()
                : TaskStep.done();
    }

    /** Resets message-specific protocol state after a consumed message fails. */
    private void reportMessageFailure(Throwable failure) {
        failureCleanup.run();
        LOG.warn("Upstream message task failed for node {}", nodeId.value(), failure);
    }
}
