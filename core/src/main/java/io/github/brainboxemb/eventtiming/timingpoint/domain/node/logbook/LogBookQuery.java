package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import java.util.function.Function;

/**
 * Typed read operation for the LogBook/Data Lane component.
 *
 * <p>ORDERED reads execute on the Data Lane. CURRENT reads are available only
 * where LogBook explicitly publishes a safe immutable/scalar view.</p>
 */
public final class LogBookQuery<R> {

    /** Determines whether a query reads published state or enters the Data Lane. */
    public enum ReadConsistency {
        /** Read the latest safely published immutable/scalar view without a lane hop. */
        CURRENT,

        /** Execute after earlier queued Data Lane work and return that ordered view. */
        ORDERED
    }

    private final String name;
    private final Function<LogBookState, R> orderedReader;
    private final Function<LogBook, R> currentReader;

    LogBookQuery(
            String name,
            Function<LogBookState, R> orderedReader) {
        this(name, orderedReader, null);
    }

    LogBookQuery(
            String name,
            Function<LogBookState, R> orderedReader,
            Function<LogBook, R> currentReader) {
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

    R readCurrent(LogBook logBook) {
        if (currentReader == null) {
            throw new IllegalArgumentException(
                    "LogBook query " + name + " does not support CURRENT consistency");
        }
        return currentReader.apply(logBook);
    }
}
