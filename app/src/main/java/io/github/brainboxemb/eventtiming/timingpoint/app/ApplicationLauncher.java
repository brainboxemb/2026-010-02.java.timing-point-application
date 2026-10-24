package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.Logging;
import io.github.brainboxemb.eventtiming.timingpoint.infra.loggingserver.LoggingServer;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.TimingApplicationRuntime;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.YamlLoader;

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
        try {
            boolean restart;
            do {
                final Config config;
                try {
                    config = YamlLoader.load(configPath);
                } catch (IOException | RuntimeException ex) {
                    // No configured log sink exists yet, so keep the build identity
                    // visible when loading configuration fails.
                    printLines(
                            out,
                            startupIdentityLines(buildIdentity));
                    throw ex;
                }
                restart =
                        runConfiguredApplication(
                                buildIdentity,
                                configPath,
                                config,
                                out);
            } while (restart);
            return 0;
        } catch (IOException | RuntimeException ex) {
            err.println(
                    "Unable to start application from configuration: "
                            + configPath
                            + " ("
                            + ex.getMessage()
                            + ")");
            return 1;
        }
    }

    /**
     * Starts cross-cutting logging before the reusable runtime and tears those
     * resources down after the TimingApplicationRuntime process returns.
     */
    private static boolean runConfiguredApplication(
            BuildIdentity buildIdentity,
            Path configPath,
            Config config,
            PrintStream out)
            throws IOException {
        Logging logging = null;
        LoggingServer loggingServer = null;
        try {
            if (config.logging() != null) {
                try {
                    logging = Logging.start(config.logging());
                } catch (IOException | RuntimeException ex) {
                    // Log initialization failed; preserve the original
                    // pre-configuration console fallback for diagnosis.
                    printLines(
                            out,
                            startupIdentityLines(buildIdentity));
                    throw ex;
                }
            }
            /*
             * Queue the complete human-readable startup context before any
             * LoggingServer, API, antenna or domain/runtime threads are started.
             * Logging.start() itself owns only the tp-inf-logging writer thread.
             */
            announceStartupIdentity(buildIdentity, logging, out);
            announceStartupConfiguration(configPath, config, logging, out);
            if (config.loggingServer() != null) {
                if (logging == null) {
                    throw new IllegalStateException(
                            "LoggingServer requires the Logging component");
                }
                loggingServer = new LoggingServer(
                        config.loggingServer(),
                        logging);
                loggingServer.start();
            }

            return runTimingApplication(
                            buildIdentity,
                            config,
                            logging)
                    == TimingApplicationRuntime.ExitRequest.RESTART;
        } finally {
            if (loggingServer != null) {
                loggingServer.close();
            }
            if (logging != null) {
                logging.close();
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
