package oss.relay.eventtiming.timingpoint.domain.node.logbook;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Typed state-changing operation for the node LogBook component.
 *
 * <p>A command is an immutable description of one state-changing domain action.
 * It may commit at most one TimingData record. Public callers create commands
 * through {@link LogBookCommands}; only the logbook package can apply them through
 * package-private {@link LogBookOperations}. {@link LogBook} owns ordered
 * execution, timeout, post-commit publication and failure handling.</p>
 */
public final class LogBookCommand<R> {
    /** Package-private command body applied by LogBook to its owned operations. */
    interface Action<R> {
        /** Applies the command body through the current Data Lane-owned LogBookOperations. */
        R apply(LogBookOperations operations) throws Exception;
    }

    private final String name;
    private final Action<R> action;

    /** Creates one immutable named command around its deferred domain action. */
    LogBookCommand(
            String name,
            Action<R> action) {

        failIfInvalidArgument(
                name != null && !name.trim().isEmpty(),
                "name must not be blank");
        failIfInvalidArgument(action != null, "action must not be null");
        this.name = name;
        this.action = action;
    }

    /** Returns the stable diagnostic operation name used by LogBook execution. */
    String name() {

        return name;
    }

    /** Applies this command through the supplied Data Lane-owned operations. */
    R apply(LogBookOperations operations) throws Exception {

        return action.apply(operations);
    }

}
