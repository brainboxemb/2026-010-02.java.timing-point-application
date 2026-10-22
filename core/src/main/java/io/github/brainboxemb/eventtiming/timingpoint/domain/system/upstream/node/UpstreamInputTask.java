package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.AbstractTask;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Owns ordered stream-message admission and the active message continuation.
 *
 * <p>One task run drains queued input, suspending on each semantic task's completion.
 * While awaiting LogBook or NodeService the shared scheduler worker remains free.
 * Subsequent messages are read only after the previous task completes.</p>
 */
final class UpstreamInputTask extends AbstractTask {
    private static final Logger LOG = LoggerFactory.getLogger(UpstreamInputTask.class);

    private final NodeId nodeId;
    private final UpstreamStream stream;
    private final BooleanSupplier available;
    private final Supplier<UpstreamData> decoder;
    private final Function<UpstreamData, YieldingTask> taskFactory;
    private final SerialTaskRunner runner;
    private final Consumer<Throwable> onMessageFailure;
    private CompletableFuture<Void> currentMessage;

    UpstreamInputTask(
            NodeId nodeId,
            UpstreamStream stream,
            BooleanSupplier available,
            Supplier<UpstreamData> decoder,
            Function<UpstreamData, YieldingTask> taskFactory,
            SerialTaskRunner runner,
            Consumer<Throwable> onMessageFailure) {
        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(stream != null, "stream must not be null");
        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(decoder != null, "decoder must not be null");
        failIfInvalidArgument(taskFactory != null, "taskFactory must not be null");
        failIfInvalidArgument(runner != null, "runner must not be null");
        failIfInvalidArgument(onMessageFailure != null, "onMessageFailure must not be null");
        this.nodeId = nodeId;
        this.stream = stream;
        this.available = available;
        this.decoder = decoder;
        this.taskFactory = taskFactory;
        this.runner = runner;
        this.onMessageFailure = onMessageFailure;
    }

    @Override
    protected void resetForRun() {
        currentMessage = null;
    }

    @Override
    public TaskStep runStep() {
        if (currentMessage != null) {
            try {
                currentMessage.join();
            } catch (CompletionException ex) {
                reportFailure(ex.getCause() == null ? ex : ex.getCause());
            }
            currentMessage = null;
        }

        if (!available.getAsBoolean() || !stream.hasMessage()) {
            return TaskStep.done();
        }

        try {
            currentMessage = runner.runTask(taskFactory.apply(decoder.get()));
        } catch (RuntimeException ex) {
            reportFailure(ex);
            return TaskStep.again();
        }
        return TaskStep.await(currentMessage);
    }

    /** Reports a consumed message failure and resets its semantic continuation. */
    private void reportFailure(Throwable failure) {
        onMessageFailure.accept(failure);
        LOG.warn("Upstream message task failed for node {}", nodeId.value(), failure);
    }

    @Override
    protected void onRunCompleted(Throwable failure) {
        if (failure != null) {
            reportFailure(failure);
        }
    }
}
