package oss.relay.eventtiming.timingpoint.infra.logging;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates one local terminal prompt with asynchronous console log output.
 *
 * <p>The first log record in a burst moves output to a fresh line. Further log
 * records postpone one prompt redraw. The coordinator is passive: it records
 * the quiet-period deadline, while the existing async logging writer calls
 * {@link #redrawPromptIfDue()} and therefore owns the timing/execution context.
 * No separate prompt thread is created.</p>
 *
 * <p>This is intentionally small and line-oriented. It does not attempt to
 * restore a partially typed command; that requires a real line editor.</p>
 */
final class ConsolePromptCoordinator
        implements ConsolePromptControl, AutoCloseable {
    private static final long PROMPT_REDRAW_DELAY_MILLIS = 150L;

    private final long redrawDelayNanos;

    private Writer output;
    private String prompt;
    private boolean promptVisible;
    private boolean burstActive;
    private long redrawDeadlineNanos;

    ConsolePromptCoordinator() {
        this(PROMPT_REDRAW_DELAY_MILLIS);
    }

    ConsolePromptCoordinator(long redrawDelayMillis) {
        if (redrawDelayMillis < 0L) {
            throw new IllegalArgumentException(
                    "redrawDelayMillis must not be negative");
        }
        redrawDelayNanos =
                TimeUnit.MILLISECONDS.toNanos(redrawDelayMillis);
    }

    @Override
    public synchronized void promptDisplayed(
            Writer output,
            String prompt) {
        if (output == null) {
            throw new IllegalArgumentException(
                    "output must not be null");
        }
        if (prompt == null || prompt.isEmpty()) {
            throw new IllegalArgumentException(
                    "prompt must not be empty");
        }

        this.output = output;
        this.prompt = prompt;
        promptVisible = true;
        burstActive = false;
        redrawDeadlineNanos = 0L;
    }

    @Override
    public synchronized void promptConsumed() {
        promptVisible = false;
        burstActive = false;
        redrawDeadlineNanos = 0L;
    }

    synchronized void beforeConsoleLog() {
        if (!promptVisible || output == null) {
            return;
        }

        write(System.lineSeparator());
        promptVisible = false;
        burstActive = true;
    }

    synchronized void afterConsoleLog() {
        if (!burstActive || output == null || prompt == null) {
            return;
        }

        redrawDeadlineNanos =
                System.nanoTime() + redrawDelayNanos;
    }

    /**
     * Returns how long the logging writer may sleep before prompt work is due.
     */
    synchronized long nextWakeDelayMillis(long maximumDelayMillis) {
        if (maximumDelayMillis < 0L) {
            throw new IllegalArgumentException(
                    "maximumDelayMillis must not be negative");
        }
        if (!hasPendingRedraw()) {
            return maximumDelayMillis;
        }

        long remaining =
                redrawDeadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            return 0L;
        }

        long remainingMillis =
                TimeUnit.NANOSECONDS.toMillis(remaining);
        if (remainingMillis == 0L) {
            remainingMillis = 1L;
        }
        return Math.min(
                maximumDelayMillis,
                remainingMillis);
    }

    /**
     * Redraws the prompt when its quiet-period deadline has elapsed.
     *
     * <p>Called only by the async logging writer in production.</p>
     */
    synchronized void redrawPromptIfDue() {
        if (!hasPendingRedraw()
                || System.nanoTime() < redrawDeadlineNanos) {
            return;
        }

        redrawDeadlineNanos = 0L;
        write(prompt);
        if (burstActive) {
            promptVisible = true;
            burstActive = false;
        }
    }

    @Override
    public synchronized void close() {
        output = null;
        prompt = null;
        promptVisible = false;
        burstActive = false;
        redrawDeadlineNanos = 0L;
    }

    private boolean hasPendingRedraw() {
        return burstActive
                && !promptVisible
                && output != null
                && prompt != null
                && redrawDeadlineNanos != 0L;
    }

    private void write(String text) {
        try {
            output.write(text);
            output.flush();
        } catch (IOException ex) {
            /*
             * Prompt decoration must never make logging or application
             * execution fail. TerminalSession still owns/report console I/O.
             */
            promptVisible = false;
            burstActive = false;
            redrawDeadlineNanos = 0L;
        }
    }
}
