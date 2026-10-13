package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.NodeDataProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TimingNodeCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TimingNodeCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TimingNodeMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TimingNodeQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TimingNodeQuery;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.TimingNodeQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.data.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamMessagePort;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.executors.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.executors.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

import java.util.concurrent.Callable;

/**
 * Top-level component boundary for one logical timing node.
 *
 * <p>TimingNode is deliberately a composition/lifecycle/facade component.
 * Mutable TimingData state, Data Lane execution, commands and queries belong to
 * {@link NodeDataProcessor}. Task/protocol execution is kept separate from that
 * data component and is migrated behind NodeTaskProcessor in the next slice.</p>
 */
public final class TimingNode {
    private final NodeDataProcessor dataProcessor;
    private final SerialExecutor taskLane;
    private final TagProcessor tagProcessor;
    private final UpstreamMessagePort upstreamMessagePort;

    /** Test-only direct-execution convenience constructor. */
    TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                EventData.empty());
    }

    /** Test-only direct-execution convenience constructor with EventData. */
    TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                eventData,
                UpstreamStream.unavailable());
    }

    /** Test-only direct-execution convenience constructor with upstream stream. */
    TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            EventData eventData,
            UpstreamStream upstreamStream) {
        this(
                new NodeDataProcessor(
                        requireId(timingNodeId),
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        eventData,
                        SystemMonotonicClock.INSTANCE,
                        new TimingNodeMetrics(),
                        new SerialExecutor(
                                32,
                                "test-node-data-" + timingNodeId.value(),
                                Runnable::run)),
                new SerialExecutor(
                        32,
                        "test-node-task-" + timingNodeId.value(),
                        Runnable::run),
                null,
                eventData,
                upstreamStream);
    }

    /**
     * Test seam for boundary tests that need explicit Data Lane scheduling or
     * operation timeout control.
     */
    TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            SerialExecutor dataLane,
            long operationTimeoutMillis) {
        this(
                new NodeDataProcessor(
                        requireId(timingNodeId),
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        EventData.empty(),
                        SystemMonotonicClock.INSTANCE,
                        new TimingNodeMetrics(),
                        dataLane,
                        operationTimeoutMillis),
                new SerialExecutor(
                        32,
                        "test-node-task-" + timingNodeId.value(),
                        Runnable::run),
                null,
                EventData.empty(),
                UpstreamStream.unavailable());
    }

    /**
     * Constructs one complete production TimingNode with runtime-supplied lanes.
     */
    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            EventData eventData,
            SerialExecutor dataLane,
            SerialExecutor taskLane,
            SerialScheduledExecutor tagProcessorExecutor) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                tagProcessingConfiguration,
                eventData,
                dataLane,
                taskLane,
                tagProcessorExecutor,
                SystemMonotonicClock.INSTANCE,
                new TimingNodeMetrics(),
                new TagProcessingMetrics(),
                UpstreamStream.unavailable());
    }

    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            EventData eventData,
            SerialExecutor dataLane,
            SerialExecutor taskLane,
            SerialScheduledExecutor tagProcessorExecutor,
            UpstreamStream upstreamStream) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                tagProcessingConfiguration,
                eventData,
                dataLane,
                taskLane,
                tagProcessorExecutor,
                SystemMonotonicClock.INSTANCE,
                new TimingNodeMetrics(),
                new TagProcessingMetrics(),
                upstreamStream);
    }

    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            EventData eventData,
            SerialExecutor dataLane,
            SerialExecutor taskLane,
            SerialScheduledExecutor tagProcessorExecutor,
            MonotonicClock monotonicClock) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                tagProcessingConfiguration,
                eventData,
                dataLane,
                taskLane,
                tagProcessorExecutor,
                monotonicClock,
                new TimingNodeMetrics(),
                new TagProcessingMetrics(),
                UpstreamStream.unavailable());
    }

    /**
     * Engineering-composition constructor retaining component-owned metrics.
     */
    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            EventData eventData,
            SerialExecutor dataLane,
            SerialExecutor taskLane,
            SerialScheduledExecutor tagProcessorExecutor,
            MonotonicClock monotonicClock,
            TimingNodeMetrics timingNodeMetrics,
            TagProcessingMetrics tagProcessingMetrics) {
        this(
                timingNodeId,
                timingDataPersistence,
                timingDataFactory,
                timeSource,
                tagProcessingConfiguration,
                eventData,
                dataLane,
                taskLane,
                tagProcessorExecutor,
                monotonicClock,
                timingNodeMetrics,
                tagProcessingMetrics,
                UpstreamStream.unavailable());
    }

    public TimingNode(
            NodeId timingNodeId,
            TimingDataPersistence timingDataPersistence,
            TimingDataFactory timingDataFactory,
            TimeSource timeSource,
            ReadOnlyConfiguration<TagProcessingPolicy> tagProcessingConfiguration,
            EventData eventData,
            SerialExecutor dataLane,
            SerialExecutor taskLane,
            SerialScheduledExecutor tagProcessorExecutor,
            MonotonicClock monotonicClock,
            TimingNodeMetrics timingNodeMetrics,
            TagProcessingMetrics tagProcessingMetrics,
            UpstreamStream upstreamStream) {
        if (tagProcessingConfiguration == null) {
            throw new IllegalArgumentException(
                    "tagProcessingConfiguration must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (taskLane == null) {
            throw new IllegalArgumentException(
                    "taskLane must not be null");
        }
        if (tagProcessorExecutor == null) {
            throw new IllegalArgumentException(
                    "tagProcessorExecutor must not be null");
        }
        if (monotonicClock == null) {
            throw new IllegalArgumentException(
                    "monotonicClock must not be null");
        }
        if (timingNodeMetrics == null) {
            throw new IllegalArgumentException(
                    "timingNodeMetrics must not be null");
        }
        if (tagProcessingMetrics == null) {
            throw new IllegalArgumentException(
                    "tagProcessingMetrics must not be null");
        }

        NodeDataProcessor processor =
                new NodeDataProcessor(
                        requireId(timingNodeId),
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        eventData,
                        monotonicClock,
                        timingNodeMetrics,
                        dataLane);

        TagProcessor processorChild =
                new TagProcessor(
                        this,
                        eventData,
                        tagProcessingConfiguration,
                        monotonicClock,
                        tagProcessingMetrics,
                        tagProcessorExecutor);

        tagProcessingConfiguration.changes()
                .subscribe(
                        processorChild::onPolicyConfigurationChanged);

        this.dataProcessor = processor;
        this.taskLane = taskLane;
        this.tagProcessor = processorChild;
        this.upstreamMessagePort =
                createUpstreamMessagePort(
                        upstreamStream,
                        eventData);
    }

    private TimingNode(
            NodeDataProcessor dataProcessor,
            SerialExecutor taskLane,
            TagProcessor tagProcessor,
            EventData eventData,
            UpstreamStream upstreamStream) {
        if (dataProcessor == null) {
            throw new IllegalArgumentException(
                    "dataProcessor must not be null");
        }
        if (taskLane == null) {
            throw new IllegalArgumentException(
                    "taskLane must not be null");
        }
        if (eventData == null) {
            throw new IllegalArgumentException(
                    "eventData must not be null");
        }
        if (upstreamStream == null) {
            throw new IllegalArgumentException(
                    "upstreamStream must not be null");
        }
        this.dataProcessor = dataProcessor;
        this.taskLane = taskLane;
        this.tagProcessor = tagProcessor;
        this.upstreamMessagePort =
                createUpstreamMessagePort(
                        upstreamStream,
                        eventData);
    }

    public NodeId timingNodeId() {
        return dataProcessor.timingNodeId();
    }

    /** Returns the TimingNode-owned semantic upstream boundary. */
    public UpstreamMessagePort upstreamMessagePort() {
        return upstreamMessagePort;
    }

    /**
     * Activates children in ownership order: Data Lane/recovery first, then
     * Task Lane, then TagProcessor ingress.
     */
    public void activate() {
        SerialExecutor.State taskState =
                taskLane.state();
        if (taskState != SerialExecutor.State.NEW
                && taskState != SerialExecutor.State.STOPPED) {
            throw new IllegalStateException(
                    "TimingNode cannot activate with Task Lane state="
                            + taskState);
        }

        dataProcessor.activate();

        try {
            taskLane.start();
        } catch (RuntimeException ex) {
            dataProcessor.deactivate();
            throw ex;
        }

        if (tagProcessor != null) {
            try {
                tagProcessor.activate();
            } catch (RuntimeException ex) {
                taskLane.close();
                dataProcessor.deactivate();
                throw ex;
            }
        }
    }

    /**
     * Stops ingress before task coordination and finally the Data Lane.
     */
    public void deactivate() {
        RuntimeException firstFailure =
                null;

        if (tagProcessor != null) {
            try {
                tagProcessor.deactivate();
            } catch (RuntimeException ex) {
                firstFailure = ex;
            }
        }

        try {
            taskLane.close();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }

        try {
            dataProcessor.deactivate();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }

        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /** Returns the node-local TagProcessor child for runtime I/O wiring. */
    public TagProcessor tagProcessor() {
        if (tagProcessor == null) {
            throw new IllegalStateException(
                    "TagProcessor is unavailable on the TimingNode test-only construction seam");
        }
        return tagProcessor;
    }

    /** Delegates one result-bearing data command to NodeDataProcessor. */
    public <R> R invoke(
            TimingNodeCommand<R> command) {
        return dataProcessor.invoke(
                command);
    }

    /** Delegates one admission-only data command to NodeDataProcessor. */
    public CommandAdmission offer(
            TimingNodeCommand<?> command) {
        return dataProcessor.offer(
                command);
    }

    public <R> R query(
            TimingNodeQuery<R> query) {
        return dataProcessor.query(
                query);
    }

    public <R> R query(
            TimingNodeQuery<R> query,
            ReadConsistency consistency) {
        return dataProcessor.query(
                query,
                consistency);
    }

    public EventSource<TimingNodeTypes.Status> statusChangedEvent() {
        return dataProcessor.statusChangedEvent();
    }

    public EventSource<TimingData> timingDataCommittedEvent() {
        return dataProcessor.timingDataCommittedEvent();
    }

    /**
     * Temporary wiring seam until upstream data access is fully asynchronous.
     */
    private UpstreamMessagePort createUpstreamMessagePort(
            UpstreamStream upstreamStream,
            EventData eventData) {
        if (upstreamStream == null) {
            throw new IllegalArgumentException(
                    "upstreamStream must not be null");
        }
        return new UpstreamMessagePort(
                timingNodeId(),
                upstreamDataLane(),
                upstreamTaskLane(),
                upstreamStream,
                eventData,
                dataProcessor.logBookForUpstream(),
                dataProcessor.teamAssignmentsForUpstream(),
                dataProcessor.stageStartTimesForUpstream(),
                dataProcessor.uniqueTeamRegistrationsForUpstream());
    }

    private UpstreamMessagePort.DataLane upstreamDataLane() {
        return new UpstreamMessagePort.DataLane() {
            @Override
            public boolean offer(
                    Runnable task) {
                return dataProcessor.offerTask(
                        task);
            }

            @Override
            public void run(
                    Runnable task,
                    String operation) {
                dataProcessor.runTask(
                        task,
                        operation);
            }

            @Override
            public <R> R run(
                    Callable<R> task,
                    String operation) {
                return dataProcessor.runTask(
                        task,
                        operation);
            }
        };
    }

    private UpstreamMessagePort.TaskLane upstreamTaskLane() {
        return new UpstreamMessagePort.TaskLane() {
            @Override
            public boolean offer(
                    Runnable task) {
                return taskLane.offer(task)
                        == SerialExecutor.AdmissionResult.ACCEPTED;
            }
        };
    }

    private static NodeId requireId(
            NodeId timingNodeId) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException(
                    "timingNodeId must not be null");
        }
        return timingNodeId;
    }
}
