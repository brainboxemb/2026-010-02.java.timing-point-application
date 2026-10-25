package oss.relay.eventtiming.timingpoint.infra.logging;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Reusable runtime logging infrastructure; the executable selects the SLF4J provider. */
public final class Logging
        implements AutoCloseable, LoggingLevelControl, ConsolePromptControl {
    private final Logger rootLogger;
    private final java.util.logging.Level previousRootLevel;
    private final Map<Handler, java.util.logging.Level> previousHandlerLevels;
    private final Map<Handler, Formatter> previousHandlerFormatters;
    private final TimestampedFileLogHandler fileHandler;
    private final AsyncLogHandler asyncHandler;
    private final List<Handler> detachedConsoleHandlers;
    private final CompactLogFormatter liveFormatter;
    private final LoggingControl control;
    private final ConsolePromptCoordinator promptCoordinator;
    private boolean closed;

    private Logging(
            Logger rootLogger,
            java.util.logging.Level previousRootLevel,
            Map<Handler, java.util.logging.Level> previousHandlerLevels,
            Map<Handler, Formatter> previousHandlerFormatters,
            TimestampedFileLogHandler fileHandler,
            AsyncLogHandler asyncHandler,
            List<Handler> detachedConsoleHandlers,
            CompactLogFormatter liveFormatter,
            LoggingControl control,
            ConsolePromptCoordinator promptCoordinator) {
        this.rootLogger = rootLogger;
        this.previousRootLevel = previousRootLevel;
        this.previousHandlerLevels = previousHandlerLevels;
        this.previousHandlerFormatters = previousHandlerFormatters;
        this.fileHandler = fileHandler;
        this.asyncHandler = asyncHandler;
        this.detachedConsoleHandlers = detachedConsoleHandlers;
        this.liveFormatter = liveFormatter;
        this.control = control;
        this.promptCoordinator = promptCoordinator;
    }

    public static Logging start(LoggingConfig config) throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("logging config must not be null");
        }

        Logger root = Logger.getLogger("");
        java.util.logging.Level previousRoot = root.getLevel();
        Map<Handler, java.util.logging.Level> previousHandlers =
                new IdentityHashMap<Handler, java.util.logging.Level>();
        Map<Handler, Formatter> previousFormatters =
                new IdentityHashMap<Handler, Formatter>();
        List<Handler> consoleHandlers =
                new ArrayList<Handler>();
        List<Handler> detachedConsoleHandlers =
                new ArrayList<Handler>();
        ConsolePromptCoordinator promptCoordinator =
                new ConsolePromptCoordinator();
        CompactLogFormatter consoleFormatter =
                new CompactLogFormatter(
                        promptCoordinator);
        CompactLogFormatter liveFormatter =
                new CompactLogFormatter();
        for (Handler handler : root.getHandlers()) {
            previousHandlers.put(handler, handler.getLevel());
            handler.setLevel(java.util.logging.Level.ALL);
            if (handler instanceof ConsoleHandler) {
                previousFormatters.put(handler, handler.getFormatter());
                handler.setFormatter(consoleFormatter);
                consoleHandlers.add(handler);
            }
        }

        TimestampedFileLogHandler fileHandler = null;
        AsyncLogHandler asyncHandler = null;
        boolean asyncAttached = false;
        try {
            LoggingControl control = new LoggingControl(root, config.level());

            LoggingFileConfig file = config.file();
            Path directory = Paths.get(file.path()).toAbsolutePath().normalize();
            fileHandler = new TimestampedFileLogHandler(
                    directory,
                    file.rotateBytes(),
                    file.retainedFiles());

            List<Handler> asynchronousSinks =
                    new ArrayList<Handler>(consoleHandlers.size() + 1);
            asynchronousSinks.addAll(consoleHandlers);
            asynchronousSinks.add(fileHandler);
            asyncHandler = new AsyncLogHandler(
                    asynchronousSinks,
                    config.queueCapacity(),
                    config.flushIntervalMillis(),
                    promptCoordinator);

            for (Handler consoleHandler : consoleHandlers) {
                root.removeHandler(consoleHandler);
                detachedConsoleHandlers.add(consoleHandler);
            }
            root.addHandler(asyncHandler);
            asyncAttached = true;

            return new Logging(
                    root,
                    previousRoot,
                    previousHandlers,
                    previousFormatters,
                    fileHandler,
                    asyncHandler,
                    new ArrayList<Handler>(detachedConsoleHandlers),
                    liveFormatter,
                    control,
                    promptCoordinator);
        } catch (IOException | RuntimeException ex) {
            if (asyncAttached && asyncHandler != null) {
                root.removeHandler(asyncHandler);
            }
            if (asyncHandler != null) {
                asyncHandler.close();
            }
            if (fileHandler != null) {
                fileHandler.close();
            }
            promptCoordinator.close();
            restore(
                    root,
                    previousRoot,
                    previousHandlers,
                    previousFormatters,
                    detachedConsoleHandlers);
            throw ex;
        }
    }

    @Override
    public LoggingLevel level() {
        return control.level();
    }

    @Override
    public void setLevel(LoggingLevel level) {
        control.setLevel(level);
    }

    public String format(LogRecord record) {
        return liveFormatter.format(record);
    }

    @Override
    public void promptDisplayed(
            Writer output,
            String prompt) {
        promptCoordinator.promptDisplayed(
                output,
                prompt);
    }

    @Override
    public void promptConsumed() {
        promptCoordinator.promptConsumed();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        rootLogger.removeHandler(asyncHandler);

        // Drain every record already accepted by the bounded queue before
        // closing the retained file or restoring synchronous console handlers.
        asyncHandler.close();
        fileHandler.close();
        promptCoordinator.close();
        restore(
                rootLogger,
                previousRootLevel,
                previousHandlerLevels,
                previousHandlerFormatters,
                detachedConsoleHandlers);
    }

    private static void restore(
            Logger root,
            java.util.logging.Level rootLevel,
            Map<Handler, java.util.logging.Level> handlerLevels,
            Map<Handler, Formatter> handlerFormatters,
            List<Handler> detachedHandlers) {
        root.setLevel(rootLevel);
        for (Map.Entry<Handler, java.util.logging.Level> entry : handlerLevels.entrySet()) {
            entry.getKey().setLevel(entry.getValue());
        }
        for (Map.Entry<Handler, Formatter> entry : handlerFormatters.entrySet()) {
            entry.getKey().setFormatter(entry.getValue());
        }
        for (Handler handler : detachedHandlers) {
            root.addHandler(handler);
        }
    }
}
