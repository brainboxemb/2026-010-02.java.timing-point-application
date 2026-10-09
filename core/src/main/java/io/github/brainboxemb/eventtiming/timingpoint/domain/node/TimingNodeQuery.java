package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import java.util.function.Function;

/**
 * Typed read operation for a TimingNode.
 *
 * <p>A query is data, not another component boundary. Public callers obtain
 * queries from {@link TimingNodeQueries}; only the timing package can bind a
 * query to the package-private {@link TimingNodeLogic}. TimingNode executes the
 * query on its serial lane so reads observe the same ordering as commands.</p>
 */
public final class TimingNodeQuery<R> {
    private final String name;
    private final Function<TimingNodeLogic, R> reader;

    TimingNodeQuery(
            String name,
            Function<TimingNodeLogic, R> reader) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        this.name = name;
        this.reader = reader;
    }

    String name() {
        return name;
    }

    R read(TimingNodeLogic logic) {
        return reader.apply(logic);
    }
}
