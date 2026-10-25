package oss.relay.timing.timingpoint.domain.node.logbook;

import java.util.concurrent.CompletableFuture;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Base data item for one operation admitted to the LogBook Data Lane.
 *
 * <p>The item carries operation data and, where needed, its result holder. It is
 * deliberately not executable work.</p>
 */
public abstract class LogBookOperationItem<R> {
    private final String name;
    private final CompletableFuture<R> result;
    private final boolean callerWaits;

    protected LogBookOperationItem(
            String name,
            boolean resultBearing,
            boolean callerWaits) {

        failIfInvalidArgument(name != null && !name.trim().isEmpty(), "name must not be blank");
        this.name = name.trim();
        this.result = resultBearing ? new CompletableFuture<R>() : null;
        this.callerWaits = callerWaits;
    }

    public final String name() {
        return name;
    }

    final CompletableFuture<R> result() {
        if (result == null) {
            throw new IllegalStateException("LogBook operation " + name + " has no result");
        }
        return result;
    }

    final boolean callerWaits() {
        return callerWaits;
    }

    final void complete(R value) {
        if (result != null) {
            result.complete(value);
        }
    }

    final void fail(Throwable failure) {
        if (result != null) {
            result.completeExceptionally(failure);
        }
    }
}
