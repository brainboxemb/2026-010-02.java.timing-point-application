package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.EmbeddedBuildIdentityLoader;
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

/**
 * Thin executable launcher for the reusable SI-01 runtime.
 *
 * <p>Startup command-line parsing stays here at the executable boundary. The
 * selected YAML file is still the authority for runtime configuration; command
 * line options only choose startup behaviour and the configuration file itself.</p>
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        BuildIdentity buildIdentity =
                EmbeddedBuildIdentityLoader.load();
        int exitCode = run(
                args,
                buildIdentity,
                System.out,
                System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Executes one startup command and returns a process exit code.
     *
     * <p>Keeping the decision logic separate from {@link #main(String[])} makes
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
        out.println(
                startupIdentityLine(
                        buildIdentity));

        try {
            Config config = YamlLoader.load(configPath);
            runConfiguredApplication(buildIdentity, config);
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
            Config config)
            throws IOException {
        Logging logging = null;
        LoggingServer loggingServer = null;
        try {
            if (config.logging() != null) {
                logging = Logging.start(config.logging());
            }
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
     * Formats the one-line identity printed at the start of normal execution.
     *
     * <p>The line is intentionally independent of configured logging so a
     * configuration or logging startup failure still records which build was
     * executed.</p>
     */
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
