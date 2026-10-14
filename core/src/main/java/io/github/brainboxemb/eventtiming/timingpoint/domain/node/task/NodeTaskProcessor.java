package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.NodeDataProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamMessagePort;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.executors.SerialExecutor;

import java.util.concurrent.Callable;

/**
 * Owns Task Lane execution and upstream composition for one TimingNode.
 *
 * <p>The Task Lane is a bounded logical serial queue, not a dedicated thread:
 * the supplied executor may share Runtime's physical worker with the Data
 * Lane. This component does not block on a Data Lane Future from a Task Lane
 * operation.</p>
 */
public final class NodeTaskProcessor {
    private final SerialExecutor taskLane;
    private final UpstreamMessagePort upstreamMessagePort;

    public NodeTaskProcessor(
            NodeDataProcessor dataProcessor,
            SerialExecutor taskLane,
            EventData eventData,
            UpstreamStream upstreamStream) {
        if (dataProcessor == null) {
            throw new IllegalArgumentException("dataProcessor must not be null");
        }
        if (taskLane == null) {
            throw new IllegalArgumentException("taskLane must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException("eventData must not be null");
        }
        if (upstreamStream == null) {
            throw new IllegalArgumentException("upstreamStream must not be null");
        }

        this.taskLane = taskLane;
        this.upstreamMessagePort =
                new UpstreamMessagePort(
                        dataProcessor.timingNodeId(),
                        new UpstreamDataLane(dataProcessor),
                        this::offerTask,
                        upstreamStream,
                        eventData,
                        dataProcessor.logBookForUpstream(),
                        dataProcessor.teamAssignmentsForUpstream(),
                        dataProcessor.stageStartTimesForUpstream(),
                        dataProcessor.uniqueTeamRegistrationsForUpstream());
    }

    /** Checks the Task Lane before Data Lane recovery is started. */
    public void ensureCanActivate() {
        SerialExecutor.State state = taskLane.state();
        if (state != SerialExecutor.State.NEW
                && state != SerialExecutor.State.STOPPED) {
            throw new IllegalStateException(
                    "TimingNode cannot activate with Task Lane state=" + state);
        }
    }

    public void activate() {
        ensureCanActivate();
        taskLane.start();
    }

    public void deactivate() {
        taskLane.close();
    }

    public UpstreamMessagePort upstreamMessagePort() {
        return upstreamMessagePort;
    }

    /** A full Task Lane rejects new work rather than blocking an ingress caller. */
    private boolean offerTask(Runnable task) {
        return taskLane.offer(task) == SerialExecutor.AdmissionResult.ACCEPTED;
    }

    /**
     * Transitional data access for the existing upstream implementation.
     *
     * <p>Inbound race changes and LogBook batches still run on Data Lane,
     * through NodeDataProcessor. A follow-up replaces the direct mutable state
     * references above with bounded asynchronous commands and immutable replies.
     * This adapter must not be used for Task Lane to Data Lane blocking calls.</p>
     */
    private static final class UpstreamDataLane implements UpstreamMessagePort.DataLane {
        private final NodeDataProcessor dataProcessor;

        UpstreamDataLane(NodeDataProcessor dataProcessor) {
            this.dataProcessor = dataProcessor;
        }

        @Override
        public boolean offer(Runnable task) {
            return dataProcessor.offerTask(task);
        }

        @Override
        public void run(Runnable task, String operation) {
            dataProcessor.runTask(task, operation);
        }

        @Override
        public <R> R run(Callable<R> task, String operation) {
            return dataProcessor.runTask(task, operation);
        }
    }
}
