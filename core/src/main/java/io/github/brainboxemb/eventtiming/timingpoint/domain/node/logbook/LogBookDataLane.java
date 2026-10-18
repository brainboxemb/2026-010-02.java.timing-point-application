package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLane;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.lanes.SerialLaneMetrics;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Bounded serial queue owned by one LogBook. */
public final class LogBookDataLane implements AutoCloseable {
    private final SerialLane<LogBookOperationItem<?>> lane;
    private Consumer<LogBookOperationItem<?>> processor;

    public LogBookDataLane(
            int capacity,
            int maxItemsPerRun,
            String laneName,
            Executor executor) {

        lane = new SerialLane<LogBookOperationItem<?>>(
                capacity,
                maxItemsPerRun,
                laneName,
                executor,
                this::process);
    }

    void bind(Consumer<LogBookOperationItem<?>> processor) {
        failIfInvalidArgument(processor != null, "processor must not be null");
        failIfIllegalState(!lane.isRunning(), "LogBook processor must be bound before Data Lane start");
        failIfIllegalState(this.processor == null, "LogBook Data Lane processor is already bound");
        this.processor = processor;
    }

    void start() {
        failIfIllegalState(processor != null, "LogBook Data Lane processor is not bound");
        lane.start();
    }

    SerialLane.OfferResult offer(LogBookOperationItem<?> item) {
        failIfInvalidArgument(item != null, "item must not be null");
        return lane.offer(item);
    }

    boolean isExecutingOnCurrentThread() {
        return lane.isExecutingOnCurrentThread();
    }

    boolean isRunning() {
        return lane.isRunning();
    }

    public SerialLaneMetrics metrics() {
        return lane.metrics();
    }

    @Override
    public void close() {
        lane.close();
    }

    private void process(LogBookOperationItem<?> item) {
        failIfIllegalState(processor != null, "LogBook Data Lane processor is not bound");
        processor.accept(item);
    }
}
