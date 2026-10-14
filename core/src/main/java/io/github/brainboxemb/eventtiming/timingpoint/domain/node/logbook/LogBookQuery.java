package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import java.util.function.Function;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

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

    /** Creates an ORDERED-only query backed by Data Lane-owned LogBookState. */
    LogBookQuery(
            String name,
            Function<LogBookState, R> orderedReader) {
        this(name, orderedReader, null);
    }

    /**
     * Creates a query with an ORDERED reader and an optional safe CURRENT reader.
     *
     * <p>The CURRENT reader receives LogBook because only LogBook may expose
     * safely published immutable/scalar state outside the Data Lane.</p>
     */
    LogBookQuery(
            String name,
            Function<LogBookState, R> orderedReader,
            Function<LogBook, R> currentReader) {
        checkArgument(
                name != null && !name.trim().isEmpty(),
                "name must not be blank");
        checkArgument(orderedReader != null, "orderedReader must not be null");
        this.name = name;
        this.orderedReader = orderedReader;
        this.currentReader = currentReader;
    }

    /** Returns the stable diagnostic name used in operation/error reporting. */
    public String name() {
        return name;
    }

    /** Reads the ordered value from Data Lane-owned mutable LogBookState. */
    R readOrdered(LogBookState state) {
        return orderedReader.apply(state);
    }

    /** Reads the safely published CURRENT view or rejects unsupported consistency. */
    R readCurrent(LogBook logBook) {
        if (currentReader == null) {
            throw new IllegalArgumentException(
                    "LogBook query " + name + " does not support CURRENT consistency");
        }
        return currentReader.apply(logBook);
    }
}
