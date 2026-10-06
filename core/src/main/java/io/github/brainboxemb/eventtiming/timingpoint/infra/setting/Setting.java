package io.github.brainboxemb.eventtiming.timingpoint.infra.setting;

import java.util.Objects;

/**
 * Holds one requested setting next to the value that has actually been applied.
 *
 * <p>The setting owns no executor, callback, retry policy or physical action.
 * Its owner processes {@link #requestedValue()} and calls
 * {@link #markApplied(Object)} only after that processing succeeds.</p>
 *
 * @param <T> setting value type
 */
public final class Setting<T> {
    private T requestedValue;
    private T appliedValue;
    private boolean changePending;
    private long requestRevision;

    public Setting(
            T initialValue) {
        requestedValue = initialValue;
        appliedValue = initialValue;
    }

    public synchronized T requestedValue() {
        return requestedValue;
    }

    public synchronized T appliedValue() {
        return appliedValue;
    }

    public synchronized boolean changePending() {
        return changePending;
    }

    /**
     * Monotonically increasing identity of the latest request.
     *
     * <p>The revision changes even when the requested value is unchanged. This
     * lets an owner distinguish a new explicit retry request from an earlier
     * failed attempt without storing a second "request received" flag.</p>
     */
    public synchronized long requestRevision() {
        return requestRevision;
    }

    /** Records the latest value the owner should eventually apply. */
    public synchronized void request(
            T value) {
        requestedValue = value;
        requestRevision++;
        updatePending();
    }

    /**
     * Records one value that was successfully applied by the owner.
     *
     * <p>The applied value can differ from the latest requested value when a
     * newer request arrived while an older change was being processed. In that
     * case {@link #changePending()} remains {@code true}.</p>
     */
    public synchronized void markApplied(
            T value) {
        appliedValue = value;
        updatePending();
    }

    private void updatePending() {
        changePending =
                !Objects.equals(
                        requestedValue,
                        appliedValue);
    }
}
