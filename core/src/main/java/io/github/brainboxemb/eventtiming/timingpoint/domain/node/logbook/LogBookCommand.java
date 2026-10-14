package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

/**
 * Typed state-changing operation for the node LogBook component.
 *
 * <p>A command is an immutable description of one state-changing domain action.
 * Public callers create commands through {@link LogBookCommands}; only the
 * logbook package can apply them to package-private {@link LogBookState}.
 * {@link LogBook} owns ordered execution, timeout and failure handling.</p>
 */
public final class LogBookCommand<R> {
    interface Action<R> {
        R apply(LogBookState logBookState) throws Exception;
    }

    private final String name;
    private final Action<R> action;

    LogBookCommand(
            String name,
            Action<R> action) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        this.name = name;
        this.action = action;
    }

    String name() {
        return name;
    }

    R apply(LogBookState logBookState) throws Exception {
        return action.apply(logBookState);
    }

}
