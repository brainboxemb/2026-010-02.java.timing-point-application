package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.CommandAdmission;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommand;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookCommands;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook.LogBookQuery.ReadConsistency;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.upstream.UpstreamHandler;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.timing.StageTime;

import java.time.ZoneId;
import java.util.List;
import io.github.brainboxemb.eventtiming.timingpoint.platform.events.EventSource;

/**
 * Top-level component boundary for one logical timing node.
 *
 * <p>TimingNode is deliberately a small lifecycle/facade component. Its child
 * processors are composed by runtime code before construction. LogBook/TimingData
 * state belongs to {@link LogBookProcessor}; task/protocol state belongs to
 * {@link NodeTaskProcessor}; tag processing belongs to {@link TagProcessor}.
 * TimingNode only connects the selected-registration event to its Data child and
 * coordinates child lifecycle.</p>
 */
public final class TimingNode {
    private final LogBookProcessor logBookProcessor;
    private final NodeTaskProcessor taskProcessor;
    private final TagProcessor tagProcessor;

    /**
     * Creates one TimingNode from its already-composed child components.
     *
     * <p>Runtime composition owns construction of the Data, Task and tag
     * processors. TimingNode owns only their lifecycle/facade relationship and
     * the registration event handoff between TagProcessor and LogBookProcessor.</p>
     */
    public TimingNode(
            LogBookProcessor logBookProcessor,
            NodeTaskProcessor taskProcessor,
            TagProcessor tagProcessor) {
        if (logBookProcessor == null) {
            throw new IllegalArgumentException(
                    "logBookProcessor must not be null");
        }
        if (taskProcessor == null) {
            throw new IllegalArgumentException(
                    "taskProcessor must not be null");
        }
        if (tagProcessor == null) {
            throw new IllegalArgumentException(
                    "tagProcessor must not be null");
        }

        this.logBookProcessor = logBookProcessor;
        this.taskProcessor = taskProcessor;
        this.tagProcessor = tagProcessor;

        tagProcessor.newRegistrationEvent()
                .subscribe(
                        this::onNewRegistration);
    }

    private void onNewRegistration(
            TagProcessor.NewRegistration registration) {
        logBookProcessor.offer(
                LogBookCommands.addAutomaticRegistration(
                        registration.registrationId(),
                        registration.observedAt()));
    }

    public NodeId timingNodeId() {
        return logBookProcessor.timingNodeId();
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

        logBookProcessor.activate();

        try {
            taskProcessor.activate();
        } catch (RuntimeException ex) {
            logBookProcessor.deactivate();
            throw ex;
        }

        try {
            tagProcessor.activate();
        } catch (RuntimeException ex) {
            taskProcessor.deactivate();
            logBookProcessor.deactivate();
            throw ex;
        }
    }

    /**
     * Stops ingress before task coordination and finally the Data Lane.
     */
    public void deactivate() {
        RuntimeException firstFailure =
                null;

        try {
            tagProcessor.deactivate();
        } catch (RuntimeException ex) {
            firstFailure = ex;
        }

        try {
            taskProcessor.deactivate();
        } catch (RuntimeException ex) {
            if (firstFailure == null) {
                firstFailure = ex;
            }
        }

        try {
            logBookProcessor.deactivate();
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
        return tagProcessor;
    }

    /** Delegates one result-bearing LogBook command to LogBookProcessor. */
    public <R> R invoke(
            LogBookCommand<R> command) {
        return logBookProcessor.invoke(
                command);
    }

    /** Delegates one admission-only LogBook command to LogBookProcessor. */
    public CommandAdmission offer(
            LogBookCommand<?> command) {
        return logBookProcessor.offer(
                command);
    }

    /** Delegates an ordered/current LogBook query to LogBookProcessor. */
    public <R> R query(LogBookQuery<R> query) {
        return logBookProcessor.query(query);
    }

    public <R> R query(
            LogBookQuery<R> query,
            ReadConsistency consistency) {
        return logBookProcessor.query(query, consistency);
    }

    /** Task Lane-owned derived timing query; deliberately not a LogBookQuery. */
    public List<StageTime> stageTiming(ZoneId raceTimeZone) {
        return taskProcessor.stageTiming(raceTimeZone);
    }

    public EventSource<TimingNodeTypes.Status> statusChangedEvent() {
        return logBookProcessor.statusChangedEvent();
    }

    /** Post-commit TimingData fact published by the Data component. */
    public EventSource<TimingData> newDataEntryEvent() {
        return logBookProcessor.newDataEntryEvent();
    }

}