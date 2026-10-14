package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.eventdata.EventData;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessorMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQueries;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.persistence.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.messaging.UpstreamStream;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.executors.SerialExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.executors.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.platform.time.TimeSource;

/**
 * Top-level component boundary for one logical timing node.
 *
 * <p>TimingNode is deliberately a composition/lifecycle/facade component.
 * Mutable TimingData state, Data Lane execution, commands and queries belong to
 * {@link LogBookProcessor}. Task/protocol execution is kept separate from that
 * data component; Task Lane execution and upstream composition belong to
 * {@link NodeTaskProcessor}.</p>
 */
public final class TimingNode {
    private final LogBookProcessor dataProcessor;
    private final NodeTaskProcessor taskProcessor;
    private final TagProcessor tagProcessor;

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
                new LogBookProcessor(
                        requireId(timingNodeId),
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        SystemMonotonicClock.INSTANCE,
                        new LogBookProcessorMetrics(),
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
                new LogBookProcessor(
                        requireId(timingNodeId),
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
                        SystemMonotonicClock.INSTANCE,
                        new LogBookProcessorMetrics(),
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
                new LogBookProcessorMetrics(),
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
                new LogBookProcessorMetrics(),
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
                new LogBookProcessorMetrics(),
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
            LogBookProcessorMetrics timingNodeMetrics,
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
            LogBookProcessorMetrics timingNodeMetrics,
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

        LogBookProcessor processor =
                new LogBookProcessor(
                        requireId(timingNodeId),
                        timingDataPersistence,
                        timingDataFactory,
                        timeSource,
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
        this.taskProcessor = new NodeTaskProcessor(
                processor,
                taskLane,
                eventData,
                upstreamStream);
        this.tagProcessor = processorChild;
    }

    private TimingNode(
            LogBookProcessor dataProcessor,
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
        this.taskProcessor = new NodeTaskProcessor(
                dataProcessor,
                taskLane,
                eventData,
                upstreamStream);
        this.tagProcessor = tagProcessor;
    }

    public NodeId timingNodeId() {
        return dataProcessor.timingNodeId();
    }

    /** Returns the TimingNode-owned semantic upstream boundary. */
    public UpstreamHandler upstreamHandler() {
        return taskProcessor.upstreamHandler();
    }

    /**
     * Activates children in ownership order: Data Lane/recovery first, then
     * Task Lane, then TagProcessor ingress.
     */
    public void activate() {
        taskProcessor.ensureCanActivate();

        dataProcessor.activate();

        try {
            taskProcessor.activate();
        } catch (RuntimeException ex) {
            dataProcessor.deactivate();
            throw ex;
        }

        if (tagProcessor != null) {
            try {
                tagProcessor.activate();
            } catch (RuntimeException ex) {
                taskProcessor.deactivate();
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
            taskProcessor.deactivate();
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

    /** Delegates one result-bearing data command to LogBookProcessor. */
    public <R> R invoke(
            LogBookCommand<R> command) {
        return dataProcessor.invoke(
                command);
    }

    /** Delegates one admission-only data command to LogBookProcessor. */
    public CommandAdmission offer(
            LogBookCommand<?> command) {
        return dataProcessor.offer(
                command);
    }

    public <R> R query(
            LogBookQuery<R> query) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        return query.isTaskOwned()
                ? taskProcessor.query(query)
                : dataProcessor.query(query);
    }

    public <R> R query(
            LogBookQuery<R> query,
            ReadConsistency consistency) {
        if (query == null) {
            throw new IllegalArgumentException("query must not be null");
        }
        if (consistency == null) {
            throw new IllegalArgumentException("consistency must not be null");
        }
        if (query.isTaskOwned()) {
            if (consistency != ReadConsistency.ORDERED) {
                throw new IllegalArgumentException(
                        "Task Lane query "
                                + query.name()
                                + " supports ORDERED consistency only");
            }
            return taskProcessor.query(query);
        }
        return dataProcessor.query(
                query,
                consistency);
    }

    public EventSource<TimingNodeTypes.Status> statusChangedEvent() {
        return dataProcessor.statusChangedEvent();
    }

    /** Post-commit TimingData fact published by the Data component. */
    public EventSource<TimingData> newDataEvent() {
        return dataProcessor.newDataEvent();
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
