package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import java.util.function.Function;

/**
 * Typed read operation for a TimingNode.
 *
 * <p>A query describes both the value being read and the consistency modes that
 * are valid for that value. ORDERED reads execute against TimingNodeLogic on the
 * node serial lane. Queries that also provide a CURRENT reader may instead read
 * a safely published immutable current representation without entering that
 * lane.</p>
 */
public final class TimingNodeQuery<R> {

    /** Consistency requested by a result-bearing TimingNode read. */
    public enum ReadConsistency {
        /** Latest safely published completed value; does not wait for queued work. */
        CURRENT,

        /** Read on the node serial lane after earlier accepted work. */
        ORDERED
    }

    private final String name;
    private final Function<TimingNodeLogic, R> orderedReader;
    private final Function<TimingNode, R> currentReader;

    TimingNodeQuery(
            String name,
            Function<TimingNodeLogic, R> orderedReader) {
        this(name, orderedReader, null);
    }

    TimingNodeQuery(
            String name,
            Function<TimingNodeLogic, R> orderedReader,
            Function<TimingNode, R> currentReader) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (orderedReader == null) {
            throw new IllegalArgumentException("orderedReader must not be null");
        }
        this.name = name;
        this.orderedReader = orderedReader;
        this.currentReader = currentReader;
    }

    String name() {
        return name;
    }

    R readOrdered(TimingNodeLogic logic) {
        return orderedReader.apply(logic);
    }

    R readCurrent(TimingNode node) {
        if (currentReader == null) {
            throw new IllegalArgumentException(
                    "TimingNode query " + name + " does not support CURRENT consistency");
        }
        return currentReader.apply(node);
    }
}
