package oss.relay.timing.timingpoint.app;

import oss.relay.timing.timingpoint.infra.BuildIdentity;
import oss.relay.timing.timingpoint.infra.logging.Logging;
import oss.relay.timing.timingpoint.infra.loggingserver.LoggingServer;
import oss.relay.timing.timingpoint.runtime.TimingApplicationRuntime;
import oss.relay.timing.timingpoint.runtime.config.Config;
import oss.relay.timing.timingpoint.runtime.config.YamlLoader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Handles command-line actions, process logging and application shutdown. */
final class ApplicationLauncher {
    private ApplicationLauncher() {
    }

    private static final Logger LOGGER =
            Logger.getLogger(ApplicationLauncher.class.getName());

    /**
     * Executes one startup command and returns a process exit code.
     *
     * <p>Keeping the decision logic separate from {@link Main#main(String[])} makes
     * help/error behaviour testable without intercepting {@code System.exit}.</p>
     */
    static int run(
            String[] args,
            BuildIdentity buildIdentity,
            PrintStream out,
            PrintStream err) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }
        if (out == null || err == null) {
            throw new IllegalArgumentException(
                    "output streams must not be null");
        }

        final StartupCommandLine commandLine;
        try {
            commandLine = StartupCommandLine.parse(args);
        } catch (IllegalArgumentException ex) {
            err.println(ex.getMessage());
            err.println();
            err.print(StartupCommandLine.helpText());
            return 2;
        }

        switch (commandLine.action()) {
            case HELP:
                out.print(StartupCommandLine.helpText());
                return 0;
            case VERSION:
                printLines(
                        out,
                        startupIdentityLines(buildIdentity));
                return 0;
            case GENERATE_CONFIG:
                return generateExampleConfiguration(
                        commandLine.path(),
                        out,
                        err);
            case SMOKE:
                runArtifactSmoke(buildIdentity, out);
                return 0;
            case START:
                return runApplication(
                        buildIdentity,
                        commandLine.path(),
                        out,
                        err);
            default:
                throw new IllegalStateException(
                        "Unsupported startup action: "
                                + commandLine.action());
        }
    }

    private static int generateExampleConfiguration(
            Path target,
            PrintStream out,
            PrintStream err) {
        try {
            ExampleConfiguration.write(target);
            out.println(
                    "Generated example configuration: "
                            + target.toAbsolutePath().normalize());
            return 0;
        } catch (IOException ex) {
            err.println(
                    "Unable to generate example configuration: "
                            + target
                            + " ("
                            + ex.getMessage()
                            + ")");
            return 1;
        }
    }

    private static int runApplication(
            BuildIdentity buildIdentity,
            Path configPath,
            PrintStream out,
            PrintStream err) {
        ProcessLoggingResources processLogging =
                new ProcessLoggingResources();
        boolean retainLogging = false;

        try {
            while (true) {
                final Config config;
                try {
                    config = YamlLoader.load(configPath);
                } catch (IOException | RuntimeException ex) {
                    if (processLogging.logging() == null) {
                        // No configured log sink exists yet, so keep the build
                        // identity visible when loading configuration fails.
                        printLines(
                                out,
                                startupIdentityLines(buildIdentity));
                    } else {
                        LOGGER.log(
                                Level.SEVERE,
                                "Unable to reload application configuration",
                                ex);
                    }
                    throw ex;
                }

                boolean retainingCurrentLogging =
                        retainLogging
                                && processLogging.logging() != null;
                if (!retainingCurrentLogging) {
                    processLogging.close();
                    processLogging.startLogging(
                            config,
                            buildIdentity,
                            out);
                } else {
                    LOGGER.info(
                            "Application reboot retaining Logging and LoggingServer; "
                                    + "reloaded logging configuration is deferred until "
                                    + "a full reboot");
                }

                /*
                 * Queue the complete human-readable startup context before a newly
                 * created LoggingServer, API, antenna or domain/runtime thread starts.
                 * On reboot +log the existing LoggingServer remains active, so the
                 * connected client also observes this restart context.
                 */
                announceStartupIdentity(
                        buildIdentity,
                        processLogging.logging(),
                        out);
                announceStartupConfiguration(
                        configPath,
                        config,
                        processLogging.logging(),
                        out);

                if (!retainingCurrentLogging) {
                    processLogging.startLoggingServer(config);
                }

                TimingApplicationRuntime.ExitRequest request =
                        runTimingApplication(
                                buildIdentity,
                                config,
                                processLogging.logging());

                if (request
                        == TimingApplicationRuntime.ExitRequest.STOP) {
                    return 0;
                }

                retainLogging =
                        request
                                == TimingApplicationRuntime.ExitRequest
                                        .RESTART_KEEP_LOGGING
                                && processLogging.logging() != null;
                if (retainLogging) {
                    LOGGER.info(
                            "Application restart requested with process logging retained");
                } else {
                    processLogging.close();
                }
            }
        } catch (IOException | RuntimeException ex) {
            err.println(
                    "Unable to start application from configuration: "
                            + configPath
                            + " ("
                            + ex.getMessage()
                            + ")");
            return 1;
        } finally {
            processLogging.close();
        }
    }

    /** Process-owned logging resources that may outlive one TimingApplicationRuntime. */
    private static final class ProcessLoggingResources
            implements AutoCloseable {
        private Logging logging;
        private LoggingServer loggingServer;

        private Logging logging() {
            return logging;
        }

        private void startLogging(
                Config config,
                BuildIdentity buildIdentity,
                PrintStream out)
                throws IOException {
            if (config.logging() == null) {
                return;
            }

            try {
                logging = Logging.start(config.logging());
            } catch (IOException | RuntimeException ex) {
                printLines(
                        out,
                        startupIdentityLines(buildIdentity));
                throw ex;
            }
        }

        private void startLoggingServer(Config config)
                throws IOException {
            if (config.loggingServer() == null) {
                return;
            }
            if (logging == null) {
                throw new IllegalStateException(
                        "LoggingServer requires the Logging component");
            }

            loggingServer =
                    new LoggingServer(
                            config.loggingServer(),
                            logging);
            loggingServer.start();
        }

        @Override
        public void close() {
            if (loggingServer != null) {
                loggingServer.close();
                loggingServer = null;
            }
            if (logging != null) {
                logging.close();
                logging = null;
            }
        }
    }

    /**
     * Runs one already configured application process.
     *
     * <p>Main owns only process concerns. TimingApplicationRuntime composition owns all
     * concrete Presentation adapters and their activation/deactivation order.</p>
     */
    private static TimingApplicationRuntime.ExitRequest runTimingApplication(
            BuildIdentity buildIdentity,
            Config config,
            Logging logging) {
        TimingApplicationRuntime application =
                TimingApplicationRuntime.create(
                        buildIdentity,
                        config,
                        logging,
                        logging,
                        new InputStreamReader(
                                System.in),
                        new OutputStreamWriter(
                                System.out));

        Runtime runtime = Runtime.getRuntime();
        Thread shutdownHook =
                new Thread(
                        application::deactivate,
                        "tp-run-shutdown");
        runtime.addShutdownHook(
                shutdownHook);

        TimingApplicationRuntime.ExitRequest request =
                TimingApplicationRuntime.ExitRequest.STOP;
        try {
            application.activate();

            try {
                request = application.awaitShutdownRequest();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } finally {
            application.deactivate();
            removeShutdownHook(
                    runtime,
                    shutdownHook);
        }

        System.out.println(
                application.smokeOutput());
        return request;
    }

    private static void removeShutdownHook(
            Runtime runtime,
            Thread shutdownHook) {
        try {
            runtime.removeShutdownHook(
                    shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }

    /**
     * Records build provenance through the normal runtime logging pipeline.
     *
     * <p>The configured logger delivers the INFO record to the retained file
     * and console handlers. Only deployments without logging use stdout;
     * failures before logging starts are handled by the startup caller.</p>
     */
    static void announceStartupIdentity(
            BuildIdentity buildIdentity,
            Logging logging,
            PrintStream out) {
        emitLines(
                startupIdentityLines(buildIdentity),
                logging,
                out,
                "announceStartupIdentity");
    }

    static void announceStartupConfiguration(
            Path configPath,
            Config config,
            Logging logging,
            PrintStream out) {
        emitLines(
                startupConfigurationLines(configPath, config),
                logging,
                out,
                "announceStartupConfiguration");
    }

    static List<String> startupIdentityLines(
            BuildIdentity buildIdentity) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }

        List<String> lines = new ArrayList<String>();
        lines.add("Application identity");
        lines.add("  Application: " + buildIdentity.application());
        lines.add("  Version: " + buildIdentity.version());
        lines.add("  API version: " + buildIdentity.apiVersion());
        lines.add("  Revision: " + buildIdentity.revision());
        lines.add("  SourceRef: " + buildIdentity.sourceRef());
        lines.add("  BuildOrigin: " + buildIdentity.buildOrigin());
        lines.add("  Dirty: " + buildIdentity.dirty());
        return lines;
    }

    static List<String> startupConfigurationLines(
            Path configPath,
            Config config) {
        if (configPath == null || config == null) {
            throw new IllegalArgumentException(
                    "Startup config path and config are required");
        }

        List<String> lines = new ArrayList<String>();
        lines.add("Startup configuration");
        lines.add("  Action: START");
        lines.add(
                "  Config: "
                        + configPath.toAbsolutePath().normalize());

        for (Config.TimingSystemConfig system
                : config.timingSystems()) {
            lines.add(
                    "  SystemId: "
                            + system.timingSystemId());
            for (Config.TimingNodeConfig node
                    : system.timingNodes()) {
                lines.add(
                        "    NodeId: "
                                + node.timingNodeId().value());
            }
            lines.add(
                    "    EventDataProvider: "
                            + system.eventDataProviderId());
            lines.add(
                    "    TimingDataProvider: "
                            + system.timingDataProviderId());
        }
        return lines;
    }

    private static void emitLines(
            List<String> lines,
            Logging logging,
            PrintStream out,
            String sourceMethod) {
        if (lines == null || out == null) {
            throw new IllegalArgumentException(
                    "startup lines and output must not be null");
        }
        for (String line : lines) {
            if (logging != null) {
                LOGGER.logp(
                        Level.INFO,
                        ApplicationLauncher.class.getName(),
                        sourceMethod,
                        line);
            } else {
                out.println(line);
            }
        }
    }

    private static void printLines(
            PrintStream out,
            List<String> lines) {
        if (out == null || lines == null) {
            throw new IllegalArgumentException(
                    "output and startup lines must not be null");
        }
        for (String line : lines) {
            out.println(line);
        }
    }

    private static void runArtifactSmoke(
            BuildIdentity buildIdentity,
            PrintStream out) {
        out.println(
                TimingApplicationRuntime.smokeOutput(
                        buildIdentity,
                        TimingApplicationRuntime.State.INACTIVE));
    }
}
