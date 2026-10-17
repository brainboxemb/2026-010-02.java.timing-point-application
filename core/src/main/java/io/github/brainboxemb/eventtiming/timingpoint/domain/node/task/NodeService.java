package io.github.brainboxemb.eventtiming.timingpoint.domain.node.task;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTiming;
import io.github.brainboxemb.eventtiming.timingpoint.infra.lifecycle.LifecycleComponent;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventInput;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.SerialTaskRunner;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.TaskStep;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.tasks.YieldingTask;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Node-local service for Task Lane-owned RaceData and derived timing.
 *
 * <p>The TimingNode owns one {@link SerialTaskRunner}. This service submits
 * one-step tasks to that runner for immutable LogBook facts and ordered queries.
 * Multi-step node startup uses the same runner, so all Task Lane work shares
 * one bounded FIFO without an additional NodeService item queue.</p>
 */
public final class NodeService extends LifecycleComponent {
    private static final Logger LOG = LoggerFactory.getLogger(NodeService.class);
    private static final Duration TASK_QUERY_TIMEOUT = Duration.ofSeconds(2L);

    private final class StageTimingTask implements YieldingTask {
        private final ZoneId raceTimeZone;
        private List<StageTime> result;

        private StageTimingTask(ZoneId raceTimeZone) {
            this.raceTimeZone = raceTimeZone;
        }

        @Override
        public TaskStep runStep() {
            result = buildStageTiming(raceTimeZone);
            return TaskStep.done();
        }

        private List<StageTime> result() {
            return result;
        }
    }

    /** Initial LogBook history input used to rebuild derived timing state. */
    public static final EventInput<NodeService, List<TimingData>> INITIAL_TIMING_DATA =
            EventInput.of(
                    (service, source) -> source.subscribe(service::onInitialTimingDataLoaded));

    /** Newly committed LogBook TimingData input used for incremental updates. */
    public static final EventInput<NodeService, TimingData> COMMITTED_TIMING_DATA =
            EventInput.of(
                    (service, source) -> source.subscribe(service::onTimingDataCommitted));

    private final NodeId nodeId;
    private final SerialTaskRunner taskRunner;
    private final NodeServiceState state;
    private final StageTiming stageTiming;

    /** Creates the service on the TimingNode-owned cooperative task runner. */
    public NodeService(
            NodeId nodeId,
            SerialTaskRunner taskRunner,
            NodeServiceState state) {

        failIfInvalidArgument(nodeId != null, "nodeId must not be null");
        failIfInvalidArgument(taskRunner != null, "taskRunner must not be null");
        failIfInvalidArgument(state != null, "state must not be null");

        this.nodeId = nodeId;
        this.taskRunner = taskRunner;
        this.state = state;
        stageTiming =
                new StageTiming(
                        state.eventData(),
                        state.teamAssignments(),
                        state.stageStartTimes());
    }

    /** TimingNode starts the shared runner before activating this service. */
    @Override
    protected void onActivate() {
        failIfIllegalState(
                taskRunner.isRunning(),
                "TimingNode task runner must be running before NodeService activation");
    }

    /** Marks derived timing unavailable when the owning node stops. */
    @Override
    protected void onDeactivate() {
        state.markDerivedTimingUnavailable();
    }

    /** Returns the current derived stage ranking in Task Lane order. */
    public List<StageTime> stageTiming(ZoneId raceTimeZone) {
        failIfInvalidArgument(raceTimeZone != null, "raceTimeZone must not be null");

        if (taskRunner.isExecutingOnCurrentThread()) {
            return buildStageTiming(raceTimeZone);
        }

        StageTimingTask task = new StageTimingTask(raceTimeZone);
        taskRunner.await(taskRunner.runTask(task), TASK_QUERY_TIMEOUT);
        return task.result();
    }

    private List<StageTime> buildStageTiming(ZoneId raceTimeZone) {
        requireTaskRunner("stageTiming");
        return stageTiming.ranking(
                state.activeRegistrations(),
                raceTimeZone);
    }

    /** Queues startup history as one Task Lane step and returns immediately. */
    private void onInitialTimingDataLoaded(List<TimingData> loadedData) {
        handoffTimingFact(
                "initial TimingData",
                () -> rebuildDerivedTiming(loadedData));
    }

    /** Queues one newly committed TimingData fact and returns immediately. */
    private void onTimingDataCommitted(TimingData data) {
        handoffTimingFact(
                "committed TimingData",
                () -> applyCommittedTimingData(data));
    }

    private void handoffTimingFact(
            String name,
            Runnable operation) {

        final CompletableFuture<Void> run;
        try {
            run =
                    taskRunner.runTask(
                            () -> {
                                operation.run();
                                return TaskStep.done();
                            });
        } catch (RuntimeException ex) {
            recordTaskFailure(name, ex);
            return;
        }

        run.whenComplete(
                (ignored, failure) -> {
                    if (failure != null && !run.isCancelled()) {
                        recordTaskFailure(name, failure);
                    }
                });
    }

    private void recordTaskFailure(
            String name,
            Throwable failure) {

        state.markDerivedTimingStale();
        LOG.error(
                "Task runner failed {} for node {}; derived timing state is stale",
                name,
                nodeId.value(),
                failure);
    }

    private void rebuildDerivedTiming(Iterable<TimingData> loadedData) {
        requireTaskRunner("rebuildDerivedTiming");
        state.rebuildDerivedTiming(loadedData);
    }

    private void applyCommittedTimingData(TimingData data) {
        requireTaskRunner("applyCommittedTimingData");
        state.applyCommittedTimingData(data);
    }

    private void requireTaskRunner(String operation) {
        failIfIllegalState(
                taskRunner.isExecutingOnCurrentThread(),
                "%s requires the TimingNode task runner",
                operation);
    }
}
