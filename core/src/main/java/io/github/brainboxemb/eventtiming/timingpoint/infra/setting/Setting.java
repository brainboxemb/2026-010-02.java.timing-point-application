package io.github.brainboxemb.eventtiming.timingpoint.infra.setting;

import java.util.Objects;

/**
 * Tracks one requested setting against the value that has actually been
 * applied.
 *
 * <p>The setting deliberately owns no executor, retry policy or device action.
 * Its owner decides when processing may start and reports the result back after
 * that processing completes.</p>
 *
 * @param <T> setting value type
 */
public final class Setting<T> {
    private T requestedValue;
    private T appliedValue;
    private boolean changePending;

    private boolean changeInProgress;
    private T processingValue;

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
     * Records the latest requested value.
     *
     * <p>Only the latest request matters. When a different request arrives
     * while an older change is still being processed, the setting remains
     * pending until the owner reports the processing result and applies the
     * latest value.</p>
     */
    public synchronized void request(
            T value) {
        requestedValue = value;
        updatePending();
    }

    /**
     * Starts processing the current requested value.
     *
     * @return {@code true} when a pending change was claimed for processing
     */
    public synchronized boolean beginChange() {
        if (changeInProgress
                || !changePending) {
            return false;
        }

        processingValue = requestedValue;
        changeInProgress = true;
        updatePending();
        return true;
    }

    /**
     * Returns the value currently being processed.
     */
    public synchronized T processingValue() {
        if (!changeInProgress) {
            throw new IllegalStateException(
                    "no Setting change is being processed");
        }
        return processingValue;
    }

    /**
     * Reports the result of the current processing attempt.
     *
     * <p>A successful result advances {@link #appliedValue()}. A failed result
     * leaves the applied value unchanged. In both cases pending state is
     * recalculated against the latest request.</p>
     */
    public synchronized void completeChange(
            boolean success) {
        if (!changeInProgress) {
            throw new IllegalStateException(
                    "no Setting change is being processed");
        }

        if (success) {
            appliedValue = processingValue;
        }

        changeInProgress = false;
        processingValue = null;
        updatePending();
    }

    private void updatePending() {
        changePending =
                !Objects.equals(
                        requestedValue,
                        appliedValue)
                || (changeInProgress
                    && !Objects.equals(
                            requestedValue,
                            processingValue));
    }
}
