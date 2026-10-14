package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

import io.github.brainboxemb.eventtiming.timingpoint.domain.node.task.NodeTaskProcessor;

import java.util.function.Function;

/**
 * Typed read operation for a TimingNode.
 *
 * <p>A query declares which logical node lane owns its ordered execution.
 * Data queries read TimingNodeLogic on the Data Lane. Task-owned derived
 * queries read NodeTaskProcessor on the Task Lane. CURRENT is available only
 * when an explicit immutable reader is supplied.</p>
 */
public final class TimingNodeQuery<R> {

    /** Consistency requested by a result-bearing TimingNode read. */
    public enum ReadConsistency {
        /** Latest safely published completed value; does not wait for queued work. */
        CURRENT,

        /** Read on the query's owning serial lane after earlier accepted work. */
        ORDERED
    }

    private enum Owner {
        DATA,
        TASK
    }

    private final String name;
    private final Owner owner;
    private final Function<TimingNodeLogic, R> dataOrderedReader;
    private final Function<NodeTaskProcessor, R> taskOrderedReader;
    private final Function<NodeDataProcessor, R> currentReader;

    TimingNodeQuery(
            String name,
            Function<TimingNodeLogic, R> orderedReader) {
        this(name, orderedReader, null);
    }

    TimingNodeQuery(
            String name,
            Function<TimingNodeLogic, R> orderedReader,
            Function<NodeDataProcessor, R> currentReader) {
        this(
                name,
                Owner.DATA,
                requireDataReader(orderedReader),
                null,
                currentReader);
    }

    private TimingNodeQuery(
            String name,
            Owner owner,
            Function<TimingNodeLogic, R> dataOrderedReader,
            Function<NodeTaskProcessor, R> taskOrderedReader,
            Function<NodeDataProcessor, R> currentReader) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.owner = owner;
        this.dataOrderedReader = dataOrderedReader;
        this.taskOrderedReader = taskOrderedReader;
        this.currentReader = currentReader;
    }

    static <R> TimingNodeQuery<R> taskOwned(
            String name,
            Function<NodeTaskProcessor, R> orderedReader) {
        if (orderedReader == null) {
            throw new IllegalArgumentException(
                    "orderedReader must not be null");
        }
        return new TimingNodeQuery<R>(
                name,
                Owner.TASK,
                null,
                orderedReader,
                null);
    }

    public String name() {
        return name;
    }

    public boolean isTaskOwned() {
        return owner == Owner.TASK;
    }

    R readOrdered(TimingNodeLogic logic) {
        if (owner != Owner.DATA) {
            throw new IllegalStateException(
                    "TimingNode query " + name + " is not Data Lane-owned");
        }
        return dataOrderedReader.apply(logic);
    }

    public R readTask(NodeTaskProcessor processor) {
        if (owner != Owner.TASK) {
            throw new IllegalStateException(
                    "TimingNode query " + name + " is not Task Lane-owned");
        }
        return taskOrderedReader.apply(processor);
    }

    R readCurrent(NodeDataProcessor processor) {
        if (currentReader == null) {
            throw new IllegalArgumentException(
                    "TimingNode query " + name + " does not support CURRENT consistency");
        }
        return currentReader.apply(processor);
    }

    private static <R> Function<TimingNodeLogic, R> requireDataReader(
            Function<TimingNodeLogic, R> reader) {
        if (reader == null) {
            throw new IllegalArgumentException(
                    "orderedReader must not be null");
        }
        return reader;
    }
}
