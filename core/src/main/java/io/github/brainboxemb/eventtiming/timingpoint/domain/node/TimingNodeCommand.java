package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

/**
 * Typed state-changing operation for a TimingNode.
 *
 * <p>A command is a value describing one domain action. Public callers obtain
 * commands from {@link TimingNodeCommands}; only the timing package binds a
 * command to package-private {@link TimingNodeLogic}. TimingNode supplies the
 * serial execution, timeout and failure boundary.</p>
 */
public final class TimingNodeCommand<R> {
    interface Action<R> {
        R apply(TimingNodeLogic logic) throws Exception;
    }

    interface Completion<R> {
        R complete(TimingNode node, R result);
    }

    private final String name;
    private final Action<R> action;
    private final Completion<R> completion;

    TimingNodeCommand(
            String name,
            Action<R> action,
            Completion<R> completion) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        if (completion == null) {
            throw new IllegalArgumentException("completion must not be null");
        }
        this.name = name;
        this.action = action;
        this.completion = completion;
    }

    String name() {
        return name;
    }

    R apply(TimingNodeLogic logic) throws Exception {
        return action.apply(logic);
    }

    R complete(TimingNode node, R result) {
        return completion.complete(node, result);
    }
}
