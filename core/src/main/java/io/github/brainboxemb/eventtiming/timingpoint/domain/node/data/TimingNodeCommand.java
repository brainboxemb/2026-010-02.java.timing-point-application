package io.github.brainboxemb.eventtiming.timingpoint.domain.node.data;

/**
 * Typed state-changing operation for a TimingNode.
 *
 * <p>A command is a value describing one domain action. Public callers obtain
 * commands from {@link TimingNodeCommands}; only this data component binds a
 * command to package-private {@link TimingNodeLogic}. {@link NodeDataProcessor}
 * supplies serial execution, timeout and failure handling.</p>
 */
public final class TimingNodeCommand<R> {
    interface Action<R> {
        R apply(TimingNodeLogic logic) throws Exception;
    }

    private final String name;
    private final Action<R> action;

    TimingNodeCommand(
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

    R apply(TimingNodeLogic logic) throws Exception {
        return action.apply(logic);
    }

}
