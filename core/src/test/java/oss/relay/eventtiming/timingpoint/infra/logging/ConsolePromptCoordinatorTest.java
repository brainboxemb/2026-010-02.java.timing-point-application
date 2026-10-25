package oss.relay.eventtiming.timingpoint.infra.logging;

import java.io.StringWriter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ConsolePromptCoordinatorTest {

    @Test
    public void logBurstMovesToFreshLineAndRedrawsPromptOnce()
            throws Exception {
        StringWriter output =
                new StringWriter();
        ConsolePromptCoordinator coordinator =
                new ConsolePromptCoordinator(
                        10L);

        coordinator.promptDisplayed(
                output,
                "event-timing> ");

        coordinator.beforeConsoleLog();
        coordinator.afterConsoleLog();
        coordinator.beforeConsoleLog();
        coordinator.afterConsoleLog();

        Thread.sleep(20L);
        coordinator.redrawPromptIfDue();
        coordinator.redrawPromptIfDue();

        assertEquals(
                System.lineSeparator()
                        + "event-timing> ",
                output.toString());
    }

    @Test
    public void submittedInputCancelsPendingPromptRedraw()
            throws Exception {
        StringWriter output =
                new StringWriter();
        ConsolePromptCoordinator coordinator =
                new ConsolePromptCoordinator(
                        10L);

        coordinator.promptDisplayed(
                output,
                "event-timing> ");
        coordinator.beforeConsoleLog();
        coordinator.afterConsoleLog();
        coordinator.promptConsumed();

        Thread.sleep(20L);
        coordinator.redrawPromptIfDue();

        assertEquals(
                System.lineSeparator(),
                output.toString());
    }

    @Test
    public void loggingWriterCanWakeForPromptBeforeItsNormalFlushInterval()
            throws Exception {
        StringWriter output =
                new StringWriter();
        ConsolePromptCoordinator coordinator =
                new ConsolePromptCoordinator(
                        10L);
        RecordingHandler sink =
                new RecordingHandler();
        AsyncLogHandler logging =
                new AsyncLogHandler(
                        java.util.Arrays.<java.util.logging.Handler>asList(sink),
                        8,
                        1000L,
                        coordinator);
        try {
            coordinator.promptDisplayed(
                    output,
                    "event-timing> ");
            coordinator.beforeConsoleLog();
            coordinator.afterConsoleLog();

            long deadline =
                    System.nanoTime()
                            + 500_000_000L;
            while (!output.toString().endsWith("event-timing> ")) {
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(
                            "Timed out waiting for prompt redraw; actual="
                                    + output);
                }
                Thread.sleep(5L);
            }
        } finally {
            logging.close();
        }

        assertEquals(
                System.lineSeparator()
                        + "event-timing> ",
                output.toString());
    }

    private static final class RecordingHandler
            extends java.util.logging.Handler {
        @Override
        public void publish(java.util.logging.LogRecord record) {
            // Prompt scheduling test does not require a real sink.
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
