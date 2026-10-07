package io.github.brainboxemb.eventtiming.timingpoint.infra.logging;

import java.io.IOException;
import java.io.Writer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates one local terminal prompt with asynchronous console log output.
 *
 * <p>The first log record in a burst moves output to a fresh line. Further log
 * records only postpone one prompt redraw. After a short quiet period the prompt
 * is rendered once again. This keeps startup/info bursts readable without
 * printing a prompt between every log line.</p>
 *
 * <p>This is intentionally small and line-oriented. It does not attempt to
 * restore a partially typed command; that requires a real line editor.</p>
 */
final class ConsolePromptCoordinator
        implements ConsolePromptControl, AutoCloseable {
    private static final long PROMPT_REDRAW_DELAY_MILLIS = 150L;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    new DaemonThreadFactory());

    private Writer output;
    private String prompt;
    private boolean promptVisible;
    private boolean burstActive;
    private ScheduledFuture<?> redrawFuture;

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
        cancelRedraw();
    }

    @Override
    public synchronized void promptConsumed() {
        promptVisible = false;
        burstActive = false;
        cancelRedraw();
    }

    synchronized void beforeConsoleLog() {
        if (!promptVisible || output == null) {
            return;
        }

        write(
                System.lineSeparator());
        promptVisible = false;
        burstActive = true;
    }

    synchronized void afterConsoleLog() {
        if (!burstActive || output == null || prompt == null) {
            return;
        }

        cancelRedraw();
        redrawFuture =
                scheduler.schedule(
                        this::redrawPromptAfterQuietPeriod,
                        PROMPT_REDRAW_DELAY_MILLIS,
                        TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        cancelRedraw();
        scheduler.shutdownNow();
        output = null;
        prompt = null;
        promptVisible = false;
        burstActive = false;
    }

    private void redrawPromptAfterQuietPeriod() {
        synchronized (this) {
            redrawFuture = null;
            if (!burstActive
                    || promptVisible
                    || output == null
                    || prompt == null) {
                return;
            }

            write(
                    prompt);
            promptVisible = true;
            burstActive = false;
        }
    }

    private void write(
            String text) {
        try {
            output.write(
                    text);
            output.flush();
        } catch (IOException ex) {
            /*
             * Prompt decoration must never make logging or application
             * execution fail. TerminalSession still owns/report console I/O.
             */
            promptVisible = false;
            burstActive = false;
            cancelRedraw();
        }
    }

    private void cancelRedraw() {
        if (redrawFuture != null) {
            redrawFuture.cancel(
                    false);
            redrawFuture = null;
        }
    }

    private static final class DaemonThreadFactory
            implements ThreadFactory {
        @Override
        public Thread newThread(
                Runnable runnable) {
            Thread thread =
                    new Thread(
                            runnable,
                            "tp-prl-prompt");
            thread.setDaemon(
                    true);
            return thread;
        }
    }
}
