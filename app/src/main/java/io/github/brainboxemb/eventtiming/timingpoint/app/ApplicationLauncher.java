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
                out.println(buildIdentity.displayName());
                out.println(buildIdentity.provenance());
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
            final Config config;
            try {
                config = YamlLoader.load(configPath);
            } catch (IOException | RuntimeException ex) {
                // No configured log sink exists yet, so keep the build identity
                // visible when loading configuration fails.
                out.println(startupIdentityLine(buildIdentity));
                throw ex;
            }
            runConfiguredApplication(buildIdentity, config, out);
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
    private static void runConfiguredApplication(
            BuildIdentity buildIdentity,
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
                    out.println(startupIdentityLine(buildIdentity));
                    throw ex;
                }
            }
            announceStartupIdentity(buildIdentity, logging, out);
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

            runTimingApplication(
                    buildIdentity,
                    config,
                    logging);
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
    private static void runTimingApplication(
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

        try {
            application.activate();

            try {
                application.awaitShutdownRequest();
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
        String identityLine = startupIdentityLine(buildIdentity);
        if (logging != null) {
            LOGGER.info(identityLine);
        } else {
            out.println(identityLine);
        }
    }

    /** Formats the canonical one-line startup identity. */
    static String startupIdentityLine(
            BuildIdentity buildIdentity) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }

        return buildIdentity.displayName()
                + " "
                + buildIdentity.provenance();
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
