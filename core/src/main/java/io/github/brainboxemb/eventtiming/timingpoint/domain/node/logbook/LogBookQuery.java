package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import java.util.function.Function;

/**
 * Typed read operation for the LogBook/Data Lane component.
 *
 * <p>ORDERED reads execute on the Data Lane. CURRENT reads are available only
 * where LogBookProcessor explicitly publishes a safe immutable/scalar view.</p>
 */
public final class LogBookQuery<R> {

    public enum ReadConsistency {
        CURRENT,
        ORDERED
    }

    private final String name;
    private final Function<LogBookState, R> orderedReader;
    private final Function<LogBookProcessor, R> currentReader;

    LogBookQuery(
            String name,
            Function<LogBookState, R> orderedReader) {
        this(name, orderedReader, null);
    }

    LogBookQuery(
            String name,
            Function<LogBookState, R> orderedReader,
            Function<LogBookProcessor, R> currentReader) {
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

    public String name() {
        return name;
    }

    R readOrdered(LogBookState state) {
        return orderedReader.apply(state);
    }

    R readCurrent(LogBookProcessor processor) {
        if (currentReader == null) {
            throw new IllegalArgumentException(
                    "LogBook query " + name + " does not support CURRENT consistency");
        }
        return currentReader.apply(processor);
    }
}
