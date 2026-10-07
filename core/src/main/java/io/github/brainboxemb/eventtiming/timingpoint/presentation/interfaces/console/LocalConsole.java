package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console;

import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.ConsolePromptControl;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal.TerminalSession;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/** Local text console for development and service use. */
public final class LocalConsole implements Runnable {
    private static final String READY_MESSAGE = "Local console ready. Type 'help' for commands.";

    private final TerminalSession session;
    private final Reader input;
    private final Writer output;

    public LocalConsole(
            PresentationGateway presentationGateway,
            Runnable shutdown,
            Reader input,
            Writer output) {
        this(
                presentationGateway,
                null,
                null,
                shutdown,
                input,
                output);
    }

    public LocalConsole(
            PresentationGateway presentationGateway,
            LoggingLevelControl loggingLevelControl,
            Runnable shutdown,
            Reader input,
            Writer output) {
        this(
                presentationGateway,
                loggingLevelControl,
                null,
                shutdown,
                input,
                output);
    }

    public LocalConsole(
            PresentationGateway presentationGateway,
            LoggingLevelControl loggingLevelControl,
            ConsolePromptControl consolePromptControl,
            Runnable shutdown,
            Reader input,
            Writer output) {
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("output must not be null");
        }
        this.session = new TerminalSession(
                presentationGateway,
                loggingLevelControl,
                consolePromptControl,
                shutdown);
        this.input = input;
        this.output = output;
    }

    @Override
    public void run() {
        try {
            session.run(input, output, READY_MESSAGE);
        } catch (IOException ex) {
            throw new IllegalStateException("Local console input failed", ex);
        }
    }
}
