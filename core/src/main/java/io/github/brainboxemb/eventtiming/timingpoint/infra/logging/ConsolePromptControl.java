package io.github.brainboxemb.eventtiming.timingpoint.infra.logging;

import java.io.Writer;

/**
 * Optional local-console prompt coordination used when asynchronous logging
 * shares the same operator terminal.
 */
public interface ConsolePromptControl {

    /**
     * Records that the local console is waiting for input with the supplied
     * prompt visible on the given output.
     */
    void promptDisplayed(
            Writer output,
            String prompt);

    /**
     * Records that the waiting prompt has been consumed by submitted input.
     */
    void promptConsumed();
}
