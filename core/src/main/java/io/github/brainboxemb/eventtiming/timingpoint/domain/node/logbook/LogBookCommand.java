package io.github.brainboxemb.eventtiming.timingpoint.domain.node.logbook;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.checkArgument;

/**
 * Typed state-changing operation for the node LogBook component.
 *
 * <p>A command is an immutable description of one state-changing domain action.
 * Public callers create commands through {@link LogBookCommands}; only the
 * logbook package can apply them to package-private {@link LogBookState}.
 * {@link LogBook} owns ordered execution, timeout and failure handling.</p>
 */
public final class LogBookCommand<R> {
    /** Package-private command body applied by LogBook to its owned state. */
    interface Action<R> {
        /** Applies the command body to the current Data Lane-owned LogBookState. */
        R apply(LogBookState logBookState) throws Exception;
    }

    private final String name;
    private final Action<R> action;

    /** Creates one immutable named command around its deferred domain action. */
    LogBookCommand(
            String name,
            Action<R> action) {
        checkArgument(
                name != null && !name.trim().isEmpty(),
                "name must not be blank");
        checkArgument(action != null, "action must not be null");
        this.name = name;
        this.action = action;
    }

    /** Returns the stable diagnostic operation name used by LogBook execution. */
    String name() {
        return name;
    }

    /** Applies this command to the supplied Data Lane-owned LogBook state. */
    R apply(LogBookState logBookState) throws Exception {
        return action.apply(logBookState);
    }

}
