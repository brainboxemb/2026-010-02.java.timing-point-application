package io.github.brainboxemb.eventtiming.timingpoint.infra.logging;

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

        try {
            coordinator.promptDisplayed(
                    output,
                    "event-timing> ");

            coordinator.beforeConsoleLog();
            coordinator.afterConsoleLog();
            coordinator.beforeConsoleLog();
            coordinator.afterConsoleLog();

            awaitOutput(
                    output,
                    System.lineSeparator()
                            + "event-timing> ");

            assertEquals(
                    System.lineSeparator()
                            + "event-timing> ",
                    output.toString());
        } finally {
            coordinator.close();
        }
    }

    @Test
    public void submittedInputCancelsPendingPromptRedraw()
            throws Exception {
        StringWriter output =
                new StringWriter();
        ConsolePromptCoordinator coordinator =
                new ConsolePromptCoordinator(
                        20L);

        try {
            coordinator.promptDisplayed(
                    output,
                    "event-timing> ");
            coordinator.beforeConsoleLog();
            coordinator.afterConsoleLog();
            coordinator.promptConsumed();

            Thread.sleep(
                    60L);

            assertEquals(
                    System.lineSeparator(),
                    output.toString());
        } finally {
            coordinator.close();
        }
    }

    private static void awaitOutput(
            StringWriter output,
            String expected)
            throws InterruptedException {
        long deadline =
                System.nanoTime()
                        + 500_000_000L;

        while (!expected.equals(
                output.toString())) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError(
                        "Timed out waiting for console output; actual="
                                + output.toString());
            }
            Thread.sleep(
                    5L);
        }
    }
}
